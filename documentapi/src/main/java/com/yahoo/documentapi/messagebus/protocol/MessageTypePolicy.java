// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.documentapi.messagebus.protocol;

import com.yahoo.config.subscription.ConfigSubscriber;
import com.yahoo.messagebus.metrics.MetricSet;
import com.yahoo.messagebus.routing.Route;
import com.yahoo.messagebus.routing.RoutingContext;
import com.yahoo.vespa.config.content.MessagetyperouteselectorpolicyConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author baldersheim
 */
public class MessageTypePolicy implements DocumentProtocolRoutingPolicy, ConfigSubscriber.SingleSubscriber<MessagetyperouteselectorpolicyConfig> {

    private final AtomicReference<Map<Integer, Route>> configRef = new AtomicReference<Map<Integer, Route>>();
    private ConfigSubscriber subscriber;
    private volatile Route defaultRoute;

    public MessageTypePolicy(String configId) {
        subscriber = new ConfigSubscriber();
        subscriber.subscribe(this, MessagetyperouteselectorpolicyConfig.class, configId);
    }

    @Override
    public void select(RoutingContext context) {
        int messageType = context.getMessage().getType();
        Route route = configRef.get().get(messageType);
        if (route == null) {
            route = defaultRoute;
        }
        context.addChild(route);
    }

    @Override
    public void merge(RoutingContext context) {
        DocumentProtocol.merge(context);
    }

    @Override
    public void destroy() {
        if (subscriber!=null) subscriber.close();
    }

    @Override
    public MetricSet getMetrics() {
        return null;
    }

    @Override
    public void configure(MessagetyperouteselectorpolicyConfig cfg) {
        Map<Integer, Route> h = new HashMap<Integer, Route>();
        for (MessagetyperouteselectorpolicyConfig.Route selector : cfg.route()) {
            h.put(selector.messagetype(), Route.parse(selector.name()));
        }
        configRef.set(h);
        defaultRoute = Route.parse(cfg.defaultroute());
    }
}
