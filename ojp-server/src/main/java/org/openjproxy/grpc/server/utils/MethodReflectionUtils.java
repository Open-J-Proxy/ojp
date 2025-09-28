package org.openjproxy.grpc.server.utils;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Utility class for method reflection operations.
 * Extracted from StatementServiceImpl to improve modularity.
 */
public class MethodReflectionUtils {

    /**
     * Finds a method by name and parameter types in the given class.
     *
     * @param clazz      The class to search in
     * @param methodName The method name
     * @param params     The parameters to match
     * @return The matching method
     * @throws RuntimeException if method is not found
     */
    public static Method findMethodByName(Class<?> clazz, String methodName, List<Object> params) {
        Method[] methods = clazz.getMethods();
        for (Method method : methods) {
            if (methodName.equalsIgnoreCase(method.getName())) {
                if (method.getParameters().length == params.size()) {
                    boolean paramTypesMatch = true;
                    for (int i = 0; i < params.size(); i++) {
                        java.lang.reflect.Parameter reflectParam = method.getParameters()[i];
                        Object receivedParam = params.get(i);
                        //TODO there is a potential issue here, if parameters are received null and more than one method receives the same amount of parameters there is no way to distinguish. Maybe send a Null object with the class type as an attribute and parse it back to null in server is a solution. This situation has not surfaced yet.
                        Class<?> reflectType = getWrapperType(reflectParam.getType());
                        if (receivedParam != null && (!reflectType.equals(receivedParam.getClass()) &&
                                !reflectType.isAssignableFrom(receivedParam.getClass()))) {
                            paramTypesMatch = false;
                            break;
                        }
                    }
                    if (paramTypesMatch) {
                        return method;
                    }
                }
            }
        }
        throw new RuntimeException("Method " + methodName + " not found in class " + clazz.getName());
    }

    /**
     * Helper method to get the wrapper class for a primitive type.
     *
     * @param primitiveType The primitive type
     * @return The wrapper class
     */
    public static Class<?> getWrapperType(Class<?> primitiveType) {
        if (primitiveType == int.class) return Integer.class;
        if (primitiveType == boolean.class) return Boolean.class;
        if (primitiveType == byte.class) return Byte.class;
        if (primitiveType == char.class) return Character.class;
        if (primitiveType == double.class) return Double.class;
        if (primitiveType == float.class) return Float.class;
        if (primitiveType == long.class) return Long.class;
        if (primitiveType == short.class) return Short.class;
        return primitiveType; // for non-primitives
    }
}