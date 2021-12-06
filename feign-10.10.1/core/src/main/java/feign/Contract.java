/**
 * Copyright 2012-2020 The Feign Authors
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import feign.Request.HttpMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static feign.Util.checkState;
import static feign.Util.emptyToNull;

/**
 * Defines what annotations and values are valid on interfaces.
 */
public interface Contract {

    /**
     * Called to parse the methods in the class that are linked to HTTP requests.
     *
     * @param targetType {@link feign.Target#type() type} of the Feign interface.
     */
    List<MethodMetadata> parseAndValidateMetadata(Class<?> targetType);

    abstract class BaseContract implements Contract {

        /**
         * @param targetType {@link feign.Target#type() type} Feign的接口类型
         * @see #parseAndValidateMetadata(Class)
         */
        @Override
        public List<MethodMetadata> parseAndValidateMetadata(Class<?> targetType) {
            // 不能存在泛型
            checkState(targetType.getTypeParameters().length == 0, "Parameterized types unsupported: %s",
                    targetType.getSimpleName());
            // 只允许至多继承单个接口
            checkState(targetType.getInterfaces().length <= 1, "Only single inheritance supported: %s",
                    targetType.getSimpleName());
            // 只允许单个继承并且该接口没有继承别的接口
            if (targetType.getInterfaces().length == 1) {
                checkState(targetType.getInterfaces()[0].getInterfaces().length == 0,
                        "Only single-level inheritance supported: %s",
                        targetType.getSimpleName());
            }
            final Map<String, MethodMetadata> result = new LinkedHashMap<String, MethodMetadata>();
            for (final Method method : targetType.getMethods()) {
                // Object的方法
                if (method.getDeclaringClass() == Object.class
                        // 静态方法
                        || (method.getModifiers() & Modifier.STATIC) != 0
                        // 接口默认方法
                        || Util.isDefault(method)) {
                    continue;
                }
                // 解析并校验元数据
                final MethodMetadata metadata = parseAndValidateMetadata(targetType, method);
                // 不支持重写的方法
                checkState(!result.containsKey(metadata.configKey()), "Overrides unsupported: %s",
                        metadata.configKey());
                result.put(metadata.configKey(), metadata);
            }
            return new ArrayList<>(result.values());
        }

        /**
         * @deprecated use {@link #parseAndValidateMetadata(Class, Method)} instead.
         */
        @Deprecated
        public MethodMetadata parseAndValidateMetadata(Method method) {
            return parseAndValidateMetadata(method.getDeclaringClass(), method);
        }

        /**
         * Called indirectly by {@link #parseAndValidateMetadata(Class)}.
         */
        protected MethodMetadata parseAndValidateMetadata(Class<?> targetType, Method method) {
            final MethodMetadata data = new MethodMetadata();
            // 声明该方法的类Class
            data.targetType(targetType);
            // 方法
            data.method(method);
            // 返回类型
            data.returnType(Types.resolve(targetType, targetType, method.getGenericReturnType()));
            // MethodMetadata 唯一标识
            data.configKey(Feign.configKey(targetType, method));
            // 当有一个接口继承
            if (targetType.getInterfaces().length == 1) {
                // Force-Spring 知识点：当FeignClient仅存在一个接口时支持处理父接口
                processAnnotationOnClass(data, targetType.getInterfaces()[0]);
            }
            // 处理类上的注解
            processAnnotationOnClass(data, targetType);


            for (final Annotation methodAnnotation : method.getAnnotations()) {
                // 处理方法上的注解
                processAnnotationOnMethod(data, methodAnnotation, method);
            }
            if (data.isIgnored()) {
                return data;
            }
            // 检查请求方式
            checkState(data.template().method() != null,
                    "Method %s not annotated with HTTP method type (ex. GET, POST)%s",
                    data.configKey(), data.warnings());
            // 参数的Class数组
            final Class<?>[] parameterTypes = method.getParameterTypes();
            final Type[] genericParameterTypes = method.getGenericParameterTypes();
            // 获取参数上的注解
            final Annotation[][] parameterAnnotations = method.getParameterAnnotations();
            final int count = parameterAnnotations.length;
            // 参数遍历
            for (int i = 0; i < count; i++) {
                boolean isHttpAnnotation = false;
                if (parameterAnnotations[i] != null) {
                    // 处理参数上的注解
                    isHttpAnnotation = processAnnotationsOnParameter(data, parameterAnnotations[i], i);
                }
                // Http的注解将被忽略
                if (isHttpAnnotation) {
                    data.ignoreParamater(i);
                }

                // 参数直接是URI
                if (parameterTypes[i] == URI.class) {
                    data.urlIndex(i);
                } else if (
                        // 非Http注解且参数不是Options
                        !isHttpAnnotation && parameterTypes[i] != Request.Options.class) {
                    // 是否已经处理过
                    if (data.isAlreadyProcessed(i)) {
                        checkState(data.formParams().isEmpty() || data.bodyIndex() == null,
                                "Body parameters cannot be used with form parameters.%s", data.warnings());
                    } else {
                        checkState(data.formParams().isEmpty(),
                                "Body parameters cannot be used with form parameters.%s", data.warnings());
                        checkState(data.bodyIndex() == null,
                                "Method has too many Body parameters: %s%s", method, data.warnings());
                        data.bodyIndex(i);

                        data.bodyType(Types.resolve(targetType, targetType, genericParameterTypes[i]));
                    }
                }
            }

            if (data.headerMapIndex() != null) {
                checkMapString("HeaderMap", parameterTypes[data.headerMapIndex()],
                        genericParameterTypes[data.headerMapIndex()]);
            }

            if (data.queryMapIndex() != null) {
                if (Map.class.isAssignableFrom(parameterTypes[data.queryMapIndex()])) {
                    checkMapKeys("QueryMap", genericParameterTypes[data.queryMapIndex()]);
                }
            }

            return data;
        }

        private static void checkMapString(String name, Class<?> type, Type genericType) {
            checkState(Map.class.isAssignableFrom(type),
                    "%s parameter must be a Map: %s", name, type);
            checkMapKeys(name, genericType);
        }

        private static void checkMapKeys(String name, Type genericType) {
            Class<?> keyClass = null;

            // assume our type parameterized
            if (ParameterizedType.class.isAssignableFrom(genericType.getClass())) {
                final Type[] parameterTypes = ((ParameterizedType) genericType).getActualTypeArguments();
                keyClass = (Class<?>) parameterTypes[0];
            } else if (genericType instanceof Class<?>) {
                // raw class, type parameters cannot be inferred directly, but we can scan any extended
                // interfaces looking for any explict types
                final Type[] interfaces = ((Class) genericType).getGenericInterfaces();
                if (interfaces != null) {
                    for (final Type extended : interfaces) {
                        if (ParameterizedType.class.isAssignableFrom(extended.getClass())) {
                            // use the first extended interface we find.
                            final Type[] parameterTypes = ((ParameterizedType) extended).getActualTypeArguments();
                            keyClass = (Class<?>) parameterTypes[0];
                            break;
                        }
                    }
                }
            }

            if (keyClass != null) {
                checkState(String.class.equals(keyClass),
                        "%s key must be a String: %s", name, keyClass.getSimpleName());
            }
        }

        /**
         * Called by parseAndValidateMetadata twice, first on the declaring class, then on the target
         * type (unless they are the same).
         *
         * @param data metadata collected so far relating to the current java method.
         * @param clz the class to process
         */
        protected abstract void processAnnotationOnClass(MethodMetadata data, Class<?> clz);

        /**
         * @param data metadata collected so far relating to the current java method.
         * @param annotation annotations present on the current method annotation.
         * @param method method currently being processed.
         */
        protected abstract void processAnnotationOnMethod(MethodMetadata data,
                                                          Annotation annotation,
                                                          Method method);

        /**
         * @param data metadata collected so far relating to the current java method.
         * @param annotations annotations present on the current parameter annotation.
         * @param paramIndex if you find a name in {@code annotations}, call
         *        {@link #nameParam(MethodMetadata, String, int)} with this as the last parameter.
         * @return true if you called {@link #nameParam(MethodMetadata, String, int)} after finding an
         *         http-relevant annotation.
         */
        protected abstract boolean processAnnotationsOnParameter(MethodMetadata data,
                                                                 Annotation[] annotations,
                                                                 int paramIndex);

        /**
         * links a parameter name to its index in the method signature.
         */
        protected void nameParam(MethodMetadata data, String name, int i) {
            final Collection<String> names =
                    data.indexToName().containsKey(i) ? data.indexToName().get(i) : new ArrayList<String>();
            names.add(name);
            data.indexToName().put(i, names);
        }
    }

    class Default extends DeclarativeContract {

        static final Pattern REQUEST_LINE_PATTERN = Pattern.compile("^([A-Z]+)[ ]*(.*)$");

        public Default() {
            super.registerClassAnnotation(Headers.class, (header, data) -> {
                final String[] headersOnType = header.value();
                checkState(headersOnType.length > 0, "Headers annotation was empty on type %s.",
                        data.configKey());
                final Map<String, Collection<String>> headers = toMap(headersOnType);
                headers.putAll(data.template().headers());
                data.template().headers(null); // to clear
                data.template().headers(headers);
            });
            super.registerMethodAnnotation(RequestLine.class, (ann, data) -> {
                final String requestLine = ann.value();
                checkState(emptyToNull(requestLine) != null,
                        "RequestLine annotation was empty on method %s.", data.configKey());

                final Matcher requestLineMatcher = REQUEST_LINE_PATTERN.matcher(requestLine);
                if (!requestLineMatcher.find()) {
                    throw new IllegalStateException(String.format(
                            "RequestLine annotation didn't start with an HTTP verb on method %s",
                            data.configKey()));
                } else {
                    data.template().method(HttpMethod.valueOf(requestLineMatcher.group(1)));
                    data.template().uri(requestLineMatcher.group(2));
                }
                data.template().decodeSlash(ann.decodeSlash());
                data.template()
                        .collectionFormat(ann.collectionFormat());
            });
            super.registerMethodAnnotation(Body.class, (ann, data) -> {
                final String body = ann.value();
                checkState(emptyToNull(body) != null, "Body annotation was empty on method %s.",
                        data.configKey());
                if (body.indexOf('{') == -1) {
                    data.template().body(body);
                } else {
                    data.template().bodyTemplate(body);
                }
            });
            super.registerMethodAnnotation(Headers.class, (header, data) -> {
                final String[] headersOnMethod = header.value();
                checkState(headersOnMethod.length > 0, "Headers annotation was empty on method %s.",
                        data.configKey());
                data.template().headers(toMap(headersOnMethod));
            });
            super.registerParameterAnnotation(Param.class, (paramAnnotation, data, paramIndex) -> {
                final String name = paramAnnotation.value();
                checkState(emptyToNull(name) != null, "Param annotation was empty on param %s.",
                        paramIndex);
                nameParam(data, name, paramIndex);
                final Class<? extends Param.Expander> expander = paramAnnotation.expander();
                if (expander != Param.ToStringExpander.class) {
                    data.indexToExpanderClass().put(paramIndex, expander);
                }
                if (!data.template().hasRequestVariable(name)) {
                    data.formParams().add(name);
                }
            });
            super.registerParameterAnnotation(QueryMap.class, (queryMap, data, paramIndex) -> {
                checkState(data.queryMapIndex() == null,
                        "QueryMap annotation was present on multiple parameters.");
                data.queryMapIndex(paramIndex);
                data.queryMapEncoded(queryMap.encoded());
            });
            super.registerParameterAnnotation(HeaderMap.class, (queryMap, data, paramIndex) -> {
                checkState(data.headerMapIndex() == null,
                        "HeaderMap annotation was present on multiple parameters.");
                data.headerMapIndex(paramIndex);
            });
        }

        private static Map<String, Collection<String>> toMap(String[] input) {
            final Map<String, Collection<String>> result =
                    new LinkedHashMap<String, Collection<String>>(input.length);
            for (final String header : input) {
                final int colon = header.indexOf(':');
                final String name = header.substring(0, colon);
                if (!result.containsKey(name)) {
                    result.put(name, new ArrayList<String>(1));
                }
                result.get(name).add(header.substring(colon + 1).trim());
            }
            return result;
        }

    }
}
