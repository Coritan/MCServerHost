package com.mcserverhost;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.mcserverhost.PluginException.ReflectionException;

import java.lang.reflect.Field;

/**
 * Reflection helpers with field-level caching
 */
public class Reflect {

	private static final Table<Class<?>, String, Field> CACHED_FIELDS_BY_NAME = HashBasedTable.create();
	private static final Table<Class<?>, Class<?>, Field> CACHED_FIELDS_BY_CLASS = HashBasedTable.create();


	public static void setFinalField(Object object, String fieldName, Object value) throws ReflectionException {
		setFinalField(object, getPrivateField(object.getClass(), fieldName), value);
	}

	public static void setFinalField(Object object, Field field, Object value) throws ReflectionException {
		field.setAccessible(true);
		setField(object, field, value);
	}

	public static void setField(Object object, String fieldName, Object value) throws ReflectionException {
		setField(object, getPrivateField(object.getClass(), fieldName), value);
	}

	public static void setField(Object object, Field field, Object value) throws ReflectionException {
		try {
			field.set(object, value);
		} catch (IllegalAccessException e) {
			throw new ReflectionException(e);
		}
	}

	public static Object getObjectInPrivateField(Object object, String fieldName) throws ReflectionException {
		Field field = getPrivateField(object.getClass(), fieldName);
		try {
			return field.get(object);
		} catch (IllegalAccessException e) {
			throw new ReflectionException(e);
		}
	}

	public static Field getPrivateField(Class<?> clazz, String fieldName) throws ReflectionException {
		Field field = getDeclaredField(clazz, fieldName);
		field.setAccessible(true);
		return field;
	}

	public static Field searchFieldByClass(Class<?> clazz, Class<?> searchFor) throws ReflectionException {
		Field cachedField = CACHED_FIELDS_BY_CLASS.get(clazz, searchFor);
		if (cachedField != null) return cachedField;

		Class<?> currentClass = clazz;
		do {
			for (Field field : currentClass.getDeclaredFields()) {
				if (!searchFor.isAssignableFrom(field.getType())) continue;
				CACHED_FIELDS_BY_CLASS.put(clazz, searchFor, field);
				return field;
			}
			currentClass = currentClass.getSuperclass();
		} while (currentClass != null);

		throw new ReflectionException("no " + searchFor.getName() + " field for clazz = " + clazz.getName() + " found");
	}

	public static Field getDeclaredField(Class<?> clazz, String fieldName) throws ReflectionException {
		Field cachedField = CACHED_FIELDS_BY_NAME.get(clazz, fieldName);
		if (cachedField != null) return cachedField;

		Field field;
		try {
			field = clazz.getDeclaredField(fieldName);
		} catch (NoSuchFieldException e) {
			Class<?> superclass = clazz.getSuperclass();
			if (superclass != null) {
				return getDeclaredField(superclass, fieldName);
			} else {
				throw new ReflectionException(e);
			}
		}

		CACHED_FIELDS_BY_NAME.put(clazz, fieldName, field);
		return field;
	}

}
