package io.pickleball.cacheandstate;

import io.pickleball.exceptions.PickleballException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;

/**
 * Abstract class providing a registry for invoking static no-arg methods in subclasses.
 */
public abstract class GlobalRegistry {
    private static final ConcurrentHashMap<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, List<Class<? extends GlobalRegistry>>> CLASS_MAP = new ConcurrentHashMap<>();
    private static boolean initialized = false;

    private static synchronized void initialize() {
        if (initialized) return;
        
        Set<Class<? extends GlobalRegistry>> subclasses = ClassScanner.findSubclasses();
        for (Class<? extends GlobalRegistry> subclass : subclasses) {
            CLASS_MAP.computeIfAbsent(subclass.getSimpleName(), k -> new ArrayList<>()).add(subclass);
        }
        initialized = true;
    }

    /**
     * Invokes a static no-arg method from a subclass.
     * @param methodPath Format: "ClassName.methodName"
     * @return Method result, or null if method/class not found
     * @throws IllegalArgumentException If method is not static or requires arguments
     */
    @SuppressWarnings("unchecked")
    public static <T> T callMethod(String methodPath) {
        if (methodPath == null || !methodPath.contains(".")) {
            
            return null;
        }
        initialize();
        Method cachedMethod = METHOD_CACHE.get(methodPath);
        if (cachedMethod != null) {
            try {
                return (T) cachedMethod.invoke(null);
            } catch (InvocationTargetException e) {
                System.err.println("Error invoking cached method " + methodPath + ": " + e.getCause().getMessage());
                throw new PickleballException(e.getCause()); // Rethrow the original cause
            } catch (IllegalAccessException e) {
                System.err.println("Error invoking cached method " + methodPath + ": " + e.getMessage());
                throw new PickleballException("Error invoking " + methodPath, e);
            }
            catch (Exception e)
            {
                throw new PickleballException("Error invoking " + methodPath, e);
            }
        }

        String[] parts = methodPath.split("\\.", 2);
        String className = parts[0];
        String methodName = parts[1];

        List<Class<? extends GlobalRegistry>> classes = CLASS_MAP.get(className);
        if (classes == null) {
            return null;
        }

        for (Class<? extends GlobalRegistry> clazz : classes) {
            
            try {
                // Scan all declared methods to find one matching the name
                Method targetMethod = null;
                for (Method method : clazz.getDeclaredMethods()) {
                    if (method.getName().equals(methodName)) {
                        targetMethod = method;
                        break;
                    }
                }
                if (targetMethod == null) {
                    
                    continue; // Try next class
                }

                
                
                if (!Modifier.isStatic(targetMethod.getModifiers())) {
                    
                    throw new IllegalArgumentException("Method " + methodPath + " is not static");
                }
                if (targetMethod.getParameterCount() > 0) {
                    
                    throw new IllegalArgumentException("Method " + methodPath + " requires arguments");
                }
                targetMethod.setAccessible(true);
                METHOD_CACHE.put(methodPath, targetMethod);
                
                return (T) targetMethod.invoke(null);
            } catch (InvocationTargetException e) {
                System.err.println("[DEBUG] Error invoking cached method " + methodPath + ": " + e.getCause().getMessage());
                throw new PickleballException(e.getCause()); // Rethrow the original cause
            }  catch (Exception e) {
                System.err.println("[DEBUG] Error invoking " + methodPath + ": " + e.getMessage());
                throw new RuntimeException("Error invoking " + methodPath, e);
            }
        }
        
        return null;
    }

    /**
     * Lists all static no-arg methods in subclasses.
     * @return Map of class names to method names
     */
    public static Map<String, List<String>> listAvailableMethods() {
        initialize();
        
        Map<String, List<String>> result = new HashMap<>();
        for (Map.Entry<String, List<Class<? extends GlobalRegistry>>> entry : CLASS_MAP.entrySet()) {
            List<String> methods = new ArrayList<>();
            for (Class<? extends GlobalRegistry> clazz : entry.getValue()) {
                
                for (Method method : clazz.getDeclaredMethods()) {
                    if (Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0) {
                        methods.add(method.getName());
                        
                    }
                }
            }
            if (!methods.isEmpty()) result.put(entry.getKey(), methods);
        }
        return result;
    }
}

/**
 * Helper class to scan for subclasses.
 */
class ClassScanner {
    @SuppressWarnings("unchecked")
    static Set<Class<? extends GlobalRegistry>> findSubclasses() {
        try {
            String packageName = "io.pickleball.configs"; // Target specific package
            Set<Class<?>> classes = ClasspathScanner.scanClasses(packageName);
            return (Set) classes.stream()
                    .filter(clazz -> GlobalRegistry.class.isAssignableFrom(clazz) && !clazz.equals(GlobalRegistry.class))
                    .collect(java.util.stream.Collectors.toSet());
        } catch (Exception e) {
            System.err.println("[DEBUG] Error scanning subclasses: " + e.getMessage());
            throw new RuntimeException("Error scanning subclasses", e);
        }
    }
}

/**
 * Helper class to scan classpath for classes.
 */
class ClasspathScanner {
    static Set<Class<?>> scanClasses(String packageName) throws Exception {
        
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        String path = packageName.replace('.', '/');
        Enumeration<java.net.URL> resources = classLoader.getResources(path);
        Set<Class<?>> classes = new java.util.HashSet<>();

        while (resources.hasMoreElements()) {
            java.net.URL resource = resources.nextElement();
            
            if ("file".equals(resource.getProtocol())) {
                classes.addAll(scanDirectory(new java.io.File(resource.getFile()), packageName, classLoader));
            } else if ("jar".equals(resource.getProtocol())) {
                classes.addAll(scanJar(resource, path, classLoader));
            }
        }
        
        return classes;
    }

    private static List<Class<?>> scanDirectory(java.io.File dir, String packageName, ClassLoader classLoader) throws ClassNotFoundException {
        List<Class<?>> classes = new ArrayList<>();
        if (!dir.exists()) {
            
            return classes;
        }
        for (java.io.File file : dir.listFiles()) {
            if (file.isDirectory()) {
                classes.addAll(scanDirectory(file, packageName + "." + file.getName(), classLoader));
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + "." + file.getName().substring(0, file.getName().length() - 6);
                try {
                    Class<?> clazz = Class.forName(className, false, classLoader);
                    classes.add(clazz);
                    
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    
                }
            }
        }
        return classes;
    }

    private static Set<Class<?>> scanJar(java.net.URL resource, String path, ClassLoader classLoader) throws Exception {
        String jarPath = resource.getPath().split("!/")[0].replace("file:", "");
        
        Set<Class<?>> classes = new java.util.HashSet<>();
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith(path) && name.endsWith(".class")) {
                    String className = name.replace('/', '.').substring(0, name.length() - 6);
                    try {
                        Class<?> clazz = Class.forName(className, false, classLoader);
                        classes.add(clazz);
                        
                    } catch (ClassNotFoundException | NoClassDefFoundError e) {
                        
                    }
                }
            }
        }
        return classes;
    }
}