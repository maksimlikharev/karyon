package com.netflix.karyon.admin.rest;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.karyon.admin.rest.exception.NotFoundException;

public class DefaultResourceContainer implements ResourceContainer {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultResourceContainer.class);
    
    private static final String CAMEL_CASE_PATTERN = "(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])"; // 3rd edit, getting better
    
    private static class Node {
        Map<String, Node> children;
        Invoker listInvoker;
        Invoker findInvoker;
        
        Node getChild(String name) {
            if (children == null) {
                return null;
            }
            return children.get(name);
        }

        Node getOrCreateChild(String name) {
            if (children == null) {
                children = new HashMap<>();
            }
            Node node = children.get(name);
            if (node == null) {
                node = new Node();
                children.put(name, node);
            }
            return node;
        }

        void setGetList(Invoker invoker) {
            this.listInvoker = invoker;
        }

        void setGetItem(Invoker invoker) {
            this.findInvoker = invoker;
        }
        
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Node[");
            boolean first = true;
            sb.append("actions=[");
            if (this.listInvoker != null) {
                sb.append("list ");
            }
            if (this.findInvoker != null) {
                sb.append("find ");
            }
            sb.append("]");
            sb.append(" children=[");
            if (children != null) {
                for (Map.Entry<String, Node> entry : children.entrySet()) {
                    if (!first) {
                        sb.append(", ");
                    }
                    first = false;
                    sb.append(entry.getKey() + ": " + entry.getValue());
                }
            }
            sb.append("]");
            sb.append("]");
            return sb.toString();
        }
    }
    
    private final Map<String, Node> resources = new HashMap<>();
    
    public DefaultResourceContainer(Map<String, Object> resource) throws Exception {
        this(resource, new DefaultStringResolverFactory());
    }
    
    public DefaultResourceContainer(Map<String, Object> resources, StringResolverFactory factory) {
        for (final Entry<String, Object> resource : resources.entrySet()) {
            Node root = new Node();
            this.resources.put(resource.getKey(), root);
            
            for (final Method method : resource.getValue().getClass().getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                
                // Determine verb
                String[] m = method.getName().split(CAMEL_CASE_PATTERN);
                Iterator<String> iter = Arrays.asList(m).iterator();
                String verb = iter.next();
                
                // Determine nesting of resources
                Node current = root;
                while (iter.hasNext()) {
                    current = current.getOrCreateChild(iter.next().toLowerCase());
                }
                
                // Determine binders for each argument
                // TODO: Bind to DI
                // TODO: Bind to Optional
                final List<IndexedStringResolver> converters = new ArrayList<>();
                int argIndex = 0;
                for (Class<?> type : method.getParameterTypes()) {
                    try {
                        converters.add(
                                new IndexedStringResolver(argIndex, 
                                        factory.create(type)));
                    } catch (Exception e) {
                        LOG.warn("Error processing parameter '{}' of {}:{}", type.getName(), resource.getValue().getClass(), method.getName());
                    }
                    argIndex++;
                }
                
                Invoker invoker = new Invoker() {
                    @Override
                    public Object invoke(List<String> args) throws Exception {
                        Object[] params = new Object[converters.size()];
                        for (int i = 0; i < converters.size(); i++) {
                            params[i] = converters.get(i).convert(args);
                        }
                        
                        return method.invoke(resource.getValue(), params);
                    }
                };
                
                if ("get".equals(verb)) {
                    if (m.length - 1 == argIndex) {
                        current.setGetList(invoker);
                    }
                    else {
                        current.setGetItem(invoker);
                    }
                }
                else {
                    LOG.warn("Invalid verb '{}' for method {}:{}.  Supported verb: [get]", verb, resource.getValue().getClass(), method.getName());
                }
            }
            // TODO: Throw exception if no methods
        }
    }
    
    @Override
    public Object invoke(String resource, List<String> parts) throws Exception {
        List<String> args = new ArrayList<>();
        Node current = resources.get(resource);
        if (current != null) {
            Iterator<String> iter = parts.iterator();
            while (iter.hasNext()) {
                String id = iter.next();
                args.add(id);
                if (iter.hasNext()) {
                    String sub = iter.next().toLowerCase();
                    current = current.getChild(sub);
                    if (current == null) {
                        throw new NotFoundException(String.format("Sub resource '%s' does not exist", sub));
                    }
                }
                else {
                    if (current.findInvoker == null) {
                        throw new NotFoundException("Invoker not found for " + parts);
                    }
                    return current.findInvoker.invoke(args);
                }
            }
            
            if (current.listInvoker == null) {
                throw new NotFoundException("Invoker not found for " + parts);
            }
            return current.listInvoker.invoke(args);
        }
        throw new NotFoundException("Resource not found for " + resource);
    }
    
    @Override
    public Set<String> getNames() {
        return resources.keySet();
    }
}
