package io.cucumber.utilities;

public class AccessFunctions {


    public static Object safeCallMethod(Object obj, String methodName) {
        if (obj == null || methodName == null) {
            return null;
        }
        try {
            Class<?> clazz = obj.getClass();
            // Find a method with the given name and no parameters
            for (var method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(methodName) && method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    return method.invoke(obj);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }








}
