// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller;

import com.yahoo.config.provision.TenantName;
import com.yahoo.vespa.curator.Lock;
import com.yahoo.vespa.hosted.controller.api.Tenant;
import com.yahoo.vespa.hosted.controller.api.identifiers.ApplicationId;
import com.yahoo.vespa.hosted.controller.api.identifiers.AthensDomain;
import com.yahoo.vespa.hosted.controller.api.identifiers.Property;
import com.yahoo.vespa.hosted.controller.api.identifiers.PropertyId;
import com.yahoo.vespa.hosted.controller.api.identifiers.TenantId;
import com.yahoo.vespa.hosted.controller.api.identifiers.UserGroup;
import com.yahoo.vespa.hosted.controller.api.identifiers.UserId;
import com.yahoo.vespa.hosted.controller.api.integration.athens.NToken;
import com.yahoo.vespa.hosted.controller.api.integration.athens.ZmsClient;
import com.yahoo.vespa.hosted.controller.api.integration.athens.ZmsClientFactory;
import com.yahoo.vespa.hosted.controller.api.integration.athens.ZmsException;
import com.yahoo.vespa.hosted.controller.api.integration.entity.EntityService;
import com.yahoo.vespa.hosted.controller.persistence.ControllerDb;
import com.yahoo.vespa.hosted.controller.persistence.CuratorDb;
import com.yahoo.vespa.hosted.controller.persistence.PersistenceException;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * A singleton owned by the Controller which contains the methods and state for controlling applications.
 *
 * @author bratseth
 */
public class TenantController {

    private static final Logger log = Logger.getLogger(TenantController.class.getName());

    /** The controller owning this */    
    private final Controller controller;

    /** For permanent storage */
    private final ControllerDb db;

    /** For working memory storage and sharing between controllers */
    private final CuratorDb curator;

    private final ZmsClientFactory zmsClientFactory;
    private final EntityService entityService;

    public TenantController(Controller controller, ControllerDb db, CuratorDb curator, EntityService entityService) {
        this.controller = controller;
        this.db = db;
        this.curator = curator;
        this.zmsClientFactory = controller.athens().zmsClientFactory();
        this.entityService = entityService;
    }

    public List<Tenant> asList() {
        return db.listTenants();
    }

    public List<Tenant> asList(UserId user) {
        Set<UserGroup> userGroups = entityService.getUserGroups(user);
        Set<AthensDomain> userDomains = new HashSet<>(zmsClientFactory.createClientWithServicePrincipal()
                                                              .getTenantDomainsForUser(controller.athens().principalFrom(user)));

        Predicate<Tenant> hasUsersGroup = (tenant) -> tenant.getUserGroup().isPresent() && userGroups.contains(tenant.getUserGroup().get());
        Predicate<Tenant> hasUsersDomain = (tenant) -> tenant.getAthensDomain().isPresent() && userDomains.contains(tenant.getAthensDomain().get());
        Predicate<Tenant> isUserTenant = (tenant) -> tenant.getId().equals(user.toTenantId());

        return asList().stream()
                .filter(t -> hasUsersGroup.test(t) || hasUsersDomain.test(t) || isUserTenant.test(t))
                .collect(Collectors.toList());
    }

    public Tenant createUserTenant(String userName) {
        TenantId userTenantId = new UserId(userName).toTenantId();
        try (Lock lock = lock(userTenantId)) {
            Tenant tenant = Tenant.createUserTenant(userTenantId);
            internalCreateTenant(tenant, Optional.empty());
            return tenant;
        }
    }

    /** Creates an Athens or OpsDb tenant. */
    // TODO: Rename to createAthensTenant and move creation here when opsDbTenant creation is removed */
    public void addTenant(Tenant tenant, Optional<NToken> token) {
        try (Lock lock = lock(tenant.getId())) {
            internalCreateTenant(tenant, token);
        }
    }

    private void internalCreateTenant(Tenant tenant, Optional<NToken> token) {
        TenantId.validate(tenant.getId().id());
        if (tenant(tenant.getId()).isPresent())
            throw new IllegalArgumentException("Tenant '" + tenant.getId() + "' already exists");
        if (tenant(dashToUnderscore(tenant.getId())).isPresent())
            throw new IllegalArgumentException("Could not create " + tenant + ": Tenant " + dashToUnderscore(tenant.getId()) + " already exists");
        if (tenant.isAthensTenant() && ! token.isPresent())
            throw new IllegalArgumentException("Could not create " + tenant + ": No NToken provided");

        if (tenant.isAthensTenant()) {
            AthensDomain domain = tenant.getAthensDomain().get();
            Optional<Tenant> existingTenantWithDomain = tenantHaving(domain);
            if (existingTenantWithDomain.isPresent())
                throw new IllegalArgumentException("Could not create " + tenant + ": The Athens domain '" + domain +
                                                   "' is already connected to " + existingTenantWithDomain.get());
            ZmsClient zmsClient = zmsClientFactory.createClientWithAuthorizedServiceToken(token.get());
            try { zmsClient.deleteTenant(domain); } catch (ZmsException ignored) { }
            zmsClient.createTenant(domain);
        }
        db.createTenant(tenant);
        log.info("Created " + tenant);
    }

    /** Returns the tenant having the given Athens domain, or empty if none */
    private Optional<Tenant> tenantHaving(AthensDomain domain) {
        return asList().stream().filter(Tenant::isAthensTenant)
                .filter(t -> t.getAthensDomain().get().equals(domain))
                .findAny();
    }

    public Optional<Tenant> tenant(TenantId id) {
        try {
            return db.getTenant(id);
        } catch (PersistenceException e) {
            throw new RuntimeException(e);
        }
    }

    public void updateTenant(Tenant updatedTenant, Optional<NToken> token) {
        try (Lock lock = lock(updatedTenant.getId())) {
            if ( ! tenant(updatedTenant.getId()).isPresent())
                throw new IllegalArgumentException("Could not update " + updatedTenant + ": Tenant does not exist");
            if (updatedTenant.isAthensTenant() && ! token.isPresent())
                throw new IllegalArgumentException("Could not update " + updatedTenant + ": No NToken provided");

            updateAthensDomain(updatedTenant, token);
            db.updateTenant(updatedTenant);
            log.info("Updated " + updatedTenant);
        } catch (PersistenceException e) {
            throw new RuntimeException(e);
        }
    }

    private void updateAthensDomain(Tenant updatedTenant, Optional<NToken> token) {
        Tenant existingTenant = tenant(updatedTenant.getId()).get();
        if ( ! existingTenant.isAthensTenant()) return;

        AthensDomain existingDomain = existingTenant.getAthensDomain().get();
        AthensDomain newDomain = updatedTenant.getAthensDomain().get();
        if (existingDomain.equals(newDomain)) return;
        Optional<Tenant> existingTenantWithNewDomain = tenantHaving(newDomain);
        if (existingTenantWithNewDomain.isPresent())
            throw new IllegalArgumentException("Could not set domain of " + updatedTenant + " to '" + newDomain +
                                               "':" + existingTenantWithNewDomain.get() + " already has this domain");

        ZmsClient zmsClient = zmsClientFactory.createClientWithAuthorizedServiceToken(token.get());
        zmsClient.createTenant(newDomain);
        List<Application> applications = controller.applications().asList(TenantName.from(existingTenant.getId().id()));
        applications.forEach(a -> zmsClient.addApplication(newDomain, new com.yahoo.vespa.hosted.controller.api.identifiers.ApplicationId(a.id().application().value())));
        applications.forEach(a -> zmsClient.deleteApplication(existingDomain, new com.yahoo.vespa.hosted.controller.api.identifiers.ApplicationId(a.id().application().value())));
        zmsClient.deleteTenant(existingDomain);
        log.info("Updated Athens domain for " + updatedTenant + " from " + existingDomain + " to " + newDomain);
    }

    public void deleteTenant(TenantId id, Optional<NToken> token) {
        try (Lock lock = lock(id)) {
            if ( ! tenant(id).isPresent())
                throw new NotExistsException(id); // TODO: Change exception and message
            if ( ! controller.applications().asList(TenantName.from(id.id())).isEmpty())
                throw new IllegalArgumentException("Could not delete tenant '" + id + "': This tenant has active applications");

            Tenant tenant = tenant(id).get();
            if (tenant.isAthensTenant() && ! token.isPresent())
                throw new IllegalArgumentException("Could not delete tenant '" + id + "': No NToken provided");

            try {
                db.deleteTenant(id);
            } catch (PersistenceException e) { // TODO: Don't allow these to leak out
                throw new RuntimeException(e);
            }
            if (tenant.isAthensTenant())
                zmsClientFactory.createClientWithAuthorizedServiceToken(token.get()).deleteTenant(tenant.getAthensDomain().get());
            log.info("Deleted " + tenant);
        }
    }

    public Tenant migrateTenantToAthens(TenantId tenantId,
                                        AthensDomain tenantDomain,
                                        PropertyId propertyId,
                                        Property property,
                                        NToken nToken) {
        try (Lock lock = lock(tenantId)) {
            Tenant existing = tenant(tenantId).orElseThrow(() -> new NotExistsException(tenantId));
            if (existing.isAthensTenant()) return existing; // nothing to do
            if (tenantHaving(tenantDomain).isPresent())
                throw new IllegalArgumentException("Could not migrate " + existing + " to " + tenantDomain + ": " +
                                                   "This domain is already used by " + tenantHaving(tenantDomain).get());
            if ( ! existing.isOpsDbTenant())
                throw new IllegalArgumentException("Could not migrate " + existing + " to " + tenantDomain + ": " +
                                                   "Tenant is not currently an OpsDb tenant");

            ZmsClient zmsClient = zmsClientFactory.createClientWithAuthorizedServiceToken(nToken);
            zmsClient.createTenant(tenantDomain);
            List<Application> applications = controller.applications().asList(TenantName.from(existing.getId().id()));
            applications.forEach(a -> {
                ApplicationId applicationId = new ApplicationId(a.id().application().value());
                zmsClient.addApplication(tenantDomain, applicationId);
            });
            db.deleteTenant(tenantId);
            Tenant tenant = Tenant.createAthensTenant(tenantId, tenantDomain, property, Optional.of(propertyId));
            db.createTenant(tenant);
            log.info("Migrated " + existing + " to Athens using " + tenantDomain);
            return tenant;
        }
        catch (PersistenceException e) {
            throw new RuntimeException("Failed migrating " + tenantId + " to Athens", e);
        }
    }

    private TenantId dashToUnderscore(TenantId id) {
        return new TenantId(id.id().replaceAll("-", "_"));
    }

    /**
     * Returns a lock which provides exclusive rights to changing this tenant.
     * Any operation which stores a tenant need to first acquire this lock, then read, modify
     * and store the tenant, and finally release (close) the lock.
     */
    private Lock lock(TenantId tenant) {
        return curator.lock(tenant, Duration.ofMinutes(10));
    }

}
