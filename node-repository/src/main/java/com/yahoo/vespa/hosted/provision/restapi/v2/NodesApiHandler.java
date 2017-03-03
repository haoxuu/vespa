// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.restapi.v2;

import com.yahoo.config.provision.HostFilter;
import com.yahoo.config.provision.NodeType;
import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.container.jdisc.LoggingRequestHandler;
import com.yahoo.container.logging.AccessLog;
import com.yahoo.io.IOUtils;
import com.yahoo.slime.ArrayTraverser;
import com.yahoo.slime.Inspector;
import com.yahoo.slime.Slime;
import com.yahoo.vespa.config.SlimeUtils;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.config.provision.NodeFlavors;
import com.yahoo.vespa.hosted.provision.node.filter.ApplicationFilter;
import com.yahoo.vespa.hosted.provision.node.filter.NodeFilter;
import com.yahoo.vespa.hosted.provision.node.filter.NodeHostFilter;
import com.yahoo.vespa.hosted.provision.node.filter.NodeTypeFilter;
import com.yahoo.vespa.hosted.provision.node.filter.ParentHostFilter;
import com.yahoo.vespa.hosted.provision.node.filter.StateFilter;
import com.yahoo.vespa.hosted.provision.restapi.v2.NodesResponse.ResponseType;
import com.yahoo.yolean.Exceptions;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.stream.Collectors;

import static com.yahoo.vespa.config.SlimeUtils.optionalString;

/**
 * The implementation of the /nodes/v2 API.
 * See RestApiTest for documentation.
 *
 * @author bratseth
 */
public class NodesApiHandler extends LoggingRequestHandler {

    private final NodeRepository nodeRepository;
    private final NodeFlavors nodeFlavors;
    private static final String nodeTypeKey = "type";


    public NodesApiHandler(Executor executor, AccessLog accessLog, NodeRepository nodeRepository, NodeFlavors flavors) {
        super(executor, accessLog);
        this.nodeRepository = nodeRepository;
        this.nodeFlavors = flavors;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        try {
            switch (request.getMethod()) {
                case GET: return handleGET(request);
                case PUT: return handlePUT(request);
                case POST: return isPatchOverride(request) ? handlePATCH(request) : handlePOST(request);
                case DELETE: return handleDELETE(request);
                case PATCH: return handlePATCH(request);
                default: return ErrorResponse.methodNotAllowed("Method '" + request.getMethod() + "' is not supported");
            }
        } 
        catch (NotFoundException | com.yahoo.vespa.hosted.provision.NotFoundException e) {
            return ErrorResponse.notFoundError(Exceptions.toMessageString(e));
        } 
        catch (IllegalArgumentException e) {
            return ErrorResponse.badRequest(Exceptions.toMessageString(e));
        }
        catch (RuntimeException e) {
            log.log(Level.WARNING, "Unexpected error handling '" + request.getUri() + "'", e);
            return ErrorResponse.internalServerError(Exceptions.toMessageString(e));
        }
    }

    private HttpResponse handleGET(HttpRequest request) {
        String path = request.getUri().getPath();
        if (path.equals(    "/nodes/v2/")) return ResourcesResponse.fromStrings(request.getUri(), "state", "node", "command");
        if (path.equals(    "/nodes/v2/node/")) return new NodesResponse(ResponseType.nodeList, request, nodeRepository);
        if (path.startsWith("/nodes/v2/node/")) return new NodesResponse(ResponseType.singleNode, request, nodeRepository);
        if (path.equals(    "/nodes/v2/state/")) return new NodesResponse(ResponseType.stateList, request, nodeRepository);
        if (path.startsWith("/nodes/v2/state/")) return new NodesResponse(ResponseType.nodesInStateList, request, nodeRepository);
        if (path.startsWith("/nodes/v2/acl/")) return new NodeAclResponse(request, nodeRepository);
        if (path.equals(    "/nodes/v2/command/")) return ResourcesResponse.fromStrings(request.getUri(), "restart", "reboot");
        throw new NotFoundException("Nothing at path '" + path + "'");
    }

    private HttpResponse handlePUT(HttpRequest request) {
        String path = request.getUri().getPath();
        // Check paths to disallow illegal state changes
        if (path.startsWith("/nodes/v2/state/ready/")) {
            nodeRepository.setReady(lastElement(path));
            return new MessageResponse("Moved " + lastElement(path) + " to ready");
        }
        else if (path.startsWith("/nodes/v2/state/failed/")) {
            List<Node> failedNodes = nodeRepository.failRecursively(lastElement(path), "Failing for unit testing");
            String failedHostnames = failedNodes.stream().map(Node::hostname).sorted().collect(Collectors.joining(", "));
            return new MessageResponse("Moved " + failedHostnames + " to failed");
        }
        else if (path.startsWith("/nodes/v2/state/parked/")) {
            List<Node> parkedNodes = nodeRepository.parkRecursively(lastElement(path));
            String parkedHostnames = parkedNodes.stream().map(Node::hostname).sorted().collect(Collectors.joining(", "));
            return new MessageResponse("Moved " + parkedHostnames + " to parked");
        }
        else if (path.startsWith("/nodes/v2/state/dirty/")) {
            nodeRepository.setDirty(lastElement(path));
            return new MessageResponse("Moved " + lastElement(path) + " to dirty");
        }
        else if (path.startsWith("/nodes/v2/state/active/")) {
            nodeRepository.reactivate(lastElement(path));
            return new MessageResponse("Moved " + lastElement(path) + " to active");
        }

        throw new NotFoundException("Cannot put to path '" + path + "'");
    }

    private HttpResponse handlePATCH(HttpRequest request) {
        String path = request.getUri().getPath();
        if ( ! path.startsWith("/nodes/v2/node/")) throw new NotFoundException("Nothing at '" + path + "'");
        Node node = nodeFromRequest(request);
        nodeRepository.write(new NodePatcher(nodeFlavors, request.getData(), node, nodeRepository.clock()).apply());
        return new MessageResponse("Updated " + node.hostname());
    }

    private HttpResponse handlePOST(HttpRequest request) {
        switch (request.getUri().getPath()) {
            case "/nodes/v2/command/restart" :
                int restartCount = nodeRepository.restart(toNodeFilter(request)).size();
                return new MessageResponse("Scheduled restart of " + restartCount + " matching nodes");
            case "/nodes/v2/command/reboot" :
                int rebootCount = nodeRepository.reboot(toNodeFilter(request)).size();
                return new MessageResponse("Scheduled reboot of " + rebootCount + " matching nodes");
            case "/nodes/v2/node" :
                int addedNodes = addNodes(request.getData());
                return new MessageResponse("Added " + addedNodes + " nodes to the provisioned state");
            default:
                throw new NotFoundException("Nothing at path '" + request.getUri().getPath() + "'");
        }
    }

    private HttpResponse handleDELETE(HttpRequest request) {
        String path = request.getUri().getPath();
        if (path.startsWith("/nodes/v2/node/")) {
            String hostname = lastElement(path);
            if (nodeRepository.remove(hostname))
                return new MessageResponse("Removed " + hostname);
            else
                throw new NotFoundException("No node in the failed state with hostname " + hostname);
        }

        throw new NotFoundException("Nothing at path '" + request.getUri().getPath() + "'");
    }

    private Node nodeFromRequest(HttpRequest request) {
        // TODO: The next 4 lines can be a oneliner when updateNodeAttribute is removed (as we won't allow path suffixes)
        String path = request.getUri().getPath();
        String prefixString = "/nodes/v2/node/";
        int beginIndex = path.indexOf(prefixString) + prefixString.length();
        int endIndex = path.indexOf("/", beginIndex);
        if (endIndex < 0) endIndex = path.length(); // path ends by ip
        String hostname = path.substring(beginIndex, endIndex);

        return nodeRepository.getNode(hostname).orElseThrow(() ->
                new NotFoundException("No node found with hostname " + hostname));
    }

    public int addNodes(InputStream jsonStream) {
        List<Node> nodes = createNodesFromSlime(toSlime(jsonStream).get());
        return nodeRepository.addNodes(nodes).size();
    }

    private Slime toSlime(InputStream jsonStream) {
        try {
            byte[] jsonBytes = IOUtils.readBytes(jsonStream, 1000 * 1000);
            return SlimeUtils.jsonToSlime(jsonBytes);
        } catch (IOException e) {
            throw new RuntimeException();
        }
    }

    private List<Node> createNodesFromSlime(Inspector object) {
        List<Node> nodes = new ArrayList<>();
        object.traverse((ArrayTraverser) (int i, Inspector item) -> nodes.add(createNode(item)));
        return nodes;
    }

    private Node createNode(Inspector inspector) {
        Optional<String> parentHostname = optionalString(inspector.field("parentHostname"));
        Optional<String> ipAddress = optionalString(inspector.field("ipAddress"));
        Set<String> ipAddresses = new HashSet<>();
        ipAddress.ifPresent(ipAddresses::add);
        inspector.field("ipAddresses").traverse((ArrayTraverser) (i, item) -> ipAddresses.add(item.asString()));

        return nodeRepository.createNode(
                inspector.field("openStackId").asString(),
                inspector.field("hostname").asString(),
                ipAddresses,
                parentHostname,
                nodeFlavors.getFlavorOrThrow(inspector.field("flavor").asString()),
                nodeTypeFromSlime(inspector.field(nodeTypeKey)));
    }

    private NodeType nodeTypeFromSlime(Inspector object) {
        if (! object.valid()) return NodeType.tenant; // default
        switch (object.asString()) {
            case "tenant" : return NodeType.tenant;
            case "host" : return NodeType.host;
            case "proxy" : return NodeType.proxy;
            default: throw new IllegalArgumentException("Unknown node type '" + object.asString() + "'");
        }
    }

    public static NodeFilter toNodeFilter(HttpRequest request) {
        NodeFilter filter = NodeHostFilter.from(HostFilter.from(request.getProperty("hostname"),
                                                                request.getProperty("flavor"),
                                                                request.getProperty("clusterType"),
                                                                request.getProperty("clusterId")));
        filter = ApplicationFilter.from(request.getProperty("application"), filter);
        filter = StateFilter.from(request.getProperty("state"), filter);
        filter = NodeTypeFilter.from(request.getProperty("type"), filter);
        filter = ParentHostFilter.from(request.getProperty("parentHost"), filter);
        return filter;
    }

    private String lastElement(String path) {
        if (path.endsWith("/"))
            path = path.substring(0, path.length()-1);
        int lastSlash = path.lastIndexOf("/");
        if (lastSlash < 0) return path;
        return path.substring(lastSlash + 1, path.length());
    }

    private boolean isPatchOverride(HttpRequest request) {
        // Since Jersey's HttpUrlConnector does not support PATCH we support this by override this on POST requests.
        String override = request.getHeader("X-HTTP-Method-Override");
        if (override != null) {
            if (override.equals("PATCH")) {
                return true;
            } else {
                String msg = String.format("Illegal X-HTTP-Method-Override header for POST request. Accepts 'PATCH' but got '%s'", override);
                throw new IllegalArgumentException(msg);
            }
        }
        return false;
    }

}
