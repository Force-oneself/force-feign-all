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

import feign.InvocationHandlerFactory.MethodHandler;
import feign.Param.Expander;
import feign.Request.Options;
import feign.codec.Decoder;
import feign.codec.EncodeException;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;
import feign.template.UriUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.Map.Entry;

import static feign.Util.checkArgument;
import static feign.Util.checkNotNull;

public class ReflectiveFeign extends Feign {

    private final ParseHandlersByName targetToHandlersByName;
    private final InvocationHandlerFactory factory;
    private final QueryMapEncoder queryMapEncoder;

    ReflectiveFeign(ParseHandlersByName targetToHandlersByName, InvocationHandlerFactory factory,
                    QueryMapEncoder queryMapEncoder) {
        this.targetToHandlersByName = targetToHandlersByName;
        this.factory = factory;
        this.queryMapEncoder = queryMapEncoder;
    }

    /**
     * creates an api binding to the {@code target}. As this invokes reflection, care should be taken
     * to cache the result.
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T> T newInstance(Target<T> target) {
        // 根据接口类和Contract协议解析方式，解析接口类上的方法和注解，转换成内部的MethodHandler处理方式
        Map<String, MethodHandler> nameToHandler = targetToHandlersByName.apply(target);
        // 方法与方法处理器的映射
        Map<Method, MethodHandler> methodToHandler = new LinkedHashMap<Method, MethodHandler>();
        // 默认方法处理器
        List<DefaultMethodHandler> defaultMethodHandlers = new LinkedList<DefaultMethodHandler>();

        for (Method method : target.type().getMethods()) {
            // Object的方法直接跳过
            if (method.getDeclaringClass() == Object.class) {
                continue;
                // 是否为默认方法，接口的默认实现方法
            } else if (Util.isDefault(method)) {
                DefaultMethodHandler handler = new DefaultMethodHandler(method);
                defaultMethodHandlers.add(handler);
                methodToHandler.put(method, handler);
            } else {
                // 从名称映射中获取到方法处理器
                methodToHandler.put(method, nameToHandler.get(Feign.configKey(target.type(), method)));
            }
        }

        // 工厂创建InvocationHandler
        InvocationHandler handler = factory.create(target, methodToHandler);
        // 创建代理
        T proxy = (T) Proxy.newProxyInstance(target.type().getClassLoader(),
                new Class<?>[]{target.type()}, handler);

        for (DefaultMethodHandler defaultMethodHandler : defaultMethodHandlers) {
            // 将接口默认方法绑定到代理类上
            defaultMethodHandler.bindTo(proxy);
        }
        return proxy;
    }

    static class FeignInvocationHandler implements InvocationHandler {

        private final Target target;
        private final Map<Method, MethodHandler> dispatch;

        FeignInvocationHandler(Target target, Map<Method, MethodHandler> dispatch) {
            this.target = checkNotNull(target, "target");
            this.dispatch = checkNotNull(dispatch, "dispatch for %s", target);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // equals方法的重写
            if ("equals".equals(method.getName())) {
                try {
                    Object otherHandler = args.length > 0 && args[0] != null
                            ? Proxy.getInvocationHandler(args[0])
                            : null;
                    return equals(otherHandler);
                } catch (IllegalArgumentException e) {
                    return false;
                }
            } else if ("hashCode".equals(method.getName())) {
                // hashCode重写
                return hashCode();
            } else if ("toString".equals(method.getName())) {
                // toString重写
                return toString();
            }

            // 其他方法通过方法与方法处理器的映射路由到方法处理器执行
            return dispatch.get(method).invoke(args);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof FeignInvocationHandler) {
                FeignInvocationHandler other = (FeignInvocationHandler) obj;
                return target.equals(other.target);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return target.hashCode();
        }

        @Override
        public String toString() {
            return target.toString();
        }
    }

    static final class ParseHandlersByName {

        private final Contract contract;
        private final Options options;
        private final Encoder encoder;
        private final Decoder decoder;
        private final ErrorDecoder errorDecoder;
        private final QueryMapEncoder queryMapEncoder;
        private final SynchronousMethodHandler.Factory factory;

        ParseHandlersByName(
                Contract contract,
                Options options,
                Encoder encoder,
                Decoder decoder,
                QueryMapEncoder queryMapEncoder,
                ErrorDecoder errorDecoder,
                SynchronousMethodHandler.Factory factory) {
            this.contract = contract;
            this.options = options;
            this.factory = factory;
            this.errorDecoder = errorDecoder;
            this.queryMapEncoder = queryMapEncoder;
            this.encoder = checkNotNull(encoder, "encoder");
            this.decoder = checkNotNull(decoder, "decoder");
        }

        public Map<String, MethodHandler> apply(Target target) {
            // Force-Spring 重点：Spring MVC 注解SpringMvcContract的解析入口
            List<MethodMetadata> metadata = contract.parseAndValidateMetadata(target.type());
            // 名称与方法处理器到映射
            Map<String, MethodHandler> result = new LinkedHashMap<String, MethodHandler>();
            for (MethodMetadata md : metadata) {
                BuildTemplateByResolvingArgs buildTemplate;

                if (!md.formParams().isEmpty()
                        && md.template().bodyTemplate() == null) {
                    buildTemplate = new BuildFormEncodedTemplateFromArgs(md, encoder, queryMapEncoder, target);
                } else if (md.bodyIndex() != null) {
                    buildTemplate = new BuildEncodedTemplateFromArgs(md, encoder, queryMapEncoder, target);
                } else {
                    buildTemplate = new BuildTemplateByResolvingArgs(md, queryMapEncoder, target);
                }

                if (md.isIgnored()) {
                    result.put(md.configKey(), args -> {
                        throw new IllegalStateException(md.configKey() + " is not a method handled by feign");
                    });
                } else {
                    result.put(md.configKey(),
                            factory.create(target, md, buildTemplate, options, decoder, errorDecoder));
                }
            }
            return result;
        }
    }

    private static class BuildTemplateByResolvingArgs implements RequestTemplate.Factory {

        private final QueryMapEncoder queryMapEncoder;

        protected final MethodMetadata metadata;
        protected final Target<?> target;
        private final Map<Integer, Expander> indexToExpander = new LinkedHashMap<Integer, Expander>();

        private BuildTemplateByResolvingArgs(MethodMetadata metadata, QueryMapEncoder queryMapEncoder,
                                             Target target) {
            this.metadata = metadata;
            this.target = target;
            this.queryMapEncoder = queryMapEncoder;
            if (metadata.indexToExpander() != null) {
                indexToExpander.putAll(metadata.indexToExpander());
                return;
            }
            if (metadata.indexToExpanderClass().isEmpty()) {
                return;
            }
            for (Entry<Integer, Class<? extends Expander>> indexToExpanderClass : metadata
                    .indexToExpanderClass().entrySet()) {
                try {
                    indexToExpander
                            .put(indexToExpanderClass.getKey(), indexToExpanderClass.getValue().newInstance());
                } catch (InstantiationException e) {
                    throw new IllegalStateException(e);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        @Override
        public RequestTemplate create(Object[] argv) {
            // 复制一份
            RequestTemplate mutable = RequestTemplate.from(metadata.template());
            mutable.feignTarget(target);
            if (metadata.urlIndex() != null) {
                int urlIndex = metadata.urlIndex();
                checkArgument(argv[urlIndex] != null, "URI parameter %s was null", urlIndex);
                // 获取uri
                mutable.target(String.valueOf(argv[urlIndex]));
            }
            Map<String, Object> varBuilder = new LinkedHashMap<String, Object>();
            for (Entry<Integer, Collection<String>> entry : metadata.indexToName().entrySet()) {
                int i = entry.getKey();
                Object value = argv[entry.getKey()];
                if (value != null) { // Null values are skipped.
                    // 存在拓展Expander
                    if (indexToExpander.containsKey(i)) {
                        // 参数转换
                        value = expandElements(indexToExpander.get(i), value);
                    }
                    for (String name : entry.getValue()) {
                        varBuilder.put(name, value);
                    }
                }
            }

            // 解析
            RequestTemplate template = resolve(argv, mutable, varBuilder);
            // 存在@QueryMap
            if (metadata.queryMapIndex() != null) {
                // 在初始解析后添加查询映射参数，以便它们优先于任何预定义值
                Object value = argv[metadata.queryMapIndex()];
                // 转换
                Map<String, Object> queryMap = toQueryMap(value);
                template = addQueryMapQueryParameters(queryMap, template);
            }

            // 存在@RequestHeader
            if (metadata.headerMapIndex() != null) {
                template = addHeaderMapHeaders((Map<String, Object>) argv[metadata.headerMapIndex()], template);
            }

            return template;
        }

        private Map<String, Object> toQueryMap(Object value) {
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
            try {
                // queryMap编码器转换
                return queryMapEncoder.encode(value);
            } catch (EncodeException e) {
                throw new IllegalStateException(e);
            }
        }

        private Object expandElements(Expander expander, Object value) {
            // Force-Spring 拓展点：Expander执行expand
            if (value instanceof Iterable) {
                return expandIterable(expander, (Iterable) value);
            }
            return expander.expand(value);
        }

        private List<String> expandIterable(Expander expander, Iterable value) {
            List<String> values = new ArrayList<String>();
            for (Object element : value) {
                if (element != null) {
                    values.add(expander.expand(element));
                }
            }
            return values;
        }

        @SuppressWarnings("unchecked")
        private RequestTemplate addHeaderMapHeaders(Map<String, Object> headerMap,
                                                    RequestTemplate mutable) {
            for (Entry<String, Object> currEntry : headerMap.entrySet()) {
                Collection<String> values = new ArrayList<String>();

                Object currValue = currEntry.getValue();
                // 迭代类型
                if (currValue instanceof Iterable<?>) {
                    Iterator<?> iter = ((Iterable<?>) currValue).iterator();
                    while (iter.hasNext()) {
                        Object nextObject = iter.next();
                        values.add(nextObject == null ? null : nextObject.toString());
                    }
                } else {
                    values.add(currValue == null ? null : currValue.toString());
                }
                // 加入header
                mutable.header(currEntry.getKey(), values);
            }
            return mutable;
        }

        @SuppressWarnings("unchecked")
        private RequestTemplate addQueryMapQueryParameters(Map<String, Object> queryMap,
                                                           RequestTemplate mutable) {
            for (Entry<String, Object> currEntry : queryMap.entrySet()) {
                Collection<String> values = new ArrayList<String>();

                boolean encoded = metadata.queryMapEncoded();
                Object currValue = currEntry.getValue();
                // 参数值为可迭代类型
                if (currValue instanceof Iterable<?>) {
                    Iterator<?> iter = ((Iterable<?>) currValue).iterator();
                    while (iter.hasNext()) {
                        Object nextObject = iter.next();
                        values.add(nextObject == null
                                ? null
                                // 编码
                                : encoded ? nextObject.toString() : UriUtils.encode(nextObject.toString()));
                    }
                } else {
                    values.add(currValue == null ? null
                            : encoded ? currValue.toString() : UriUtils.encode(currValue.toString()));
                }

                mutable.query(encoded ? currEntry.getKey() : UriUtils.encode(currEntry.getKey()), values);
            }
            return mutable;
        }

        protected RequestTemplate resolve(Object[] argv,
                                          RequestTemplate mutable,
                                          Map<String, Object> variables) {
            return mutable.resolve(variables);
        }
    }

    private static class BuildFormEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

        private final Encoder encoder;

        private BuildFormEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder,
                                                 QueryMapEncoder queryMapEncoder, Target target) {
            super(metadata, queryMapEncoder, target);
            this.encoder = encoder;
        }

        @Override
        protected RequestTemplate resolve(Object[] argv,
                                          RequestTemplate mutable,
                                          Map<String, Object> variables) {
            Map<String, Object> formVariables = new LinkedHashMap<String, Object>();
            for (Entry<String, Object> entry : variables.entrySet()) {
                if (metadata.formParams().contains(entry.getKey())) {
                    formVariables.put(entry.getKey(), entry.getValue());
                }
            }
            try {
                encoder.encode(formVariables, Encoder.MAP_STRING_WILDCARD, mutable);
            } catch (EncodeException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new EncodeException(e.getMessage(), e);
            }
            return super.resolve(argv, mutable, variables);
        }
    }

    private static class BuildEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

        private final Encoder encoder;

        private BuildEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder,
                                             QueryMapEncoder queryMapEncoder, Target target) {
            super(metadata, queryMapEncoder, target);
            this.encoder = encoder;
        }

        @Override
        protected RequestTemplate resolve(Object[] argv,
                                          RequestTemplate mutable,
                                          Map<String, Object> variables) {
            Object body = argv[metadata.bodyIndex()];
            checkArgument(body != null, "Body parameter %s was null", metadata.bodyIndex());
            try {
                encoder.encode(body, metadata.bodyType(), mutable);
            } catch (EncodeException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new EncodeException(e.getMessage(), e);
            }
            return super.resolve(argv, mutable, variables);
        }
    }
}
