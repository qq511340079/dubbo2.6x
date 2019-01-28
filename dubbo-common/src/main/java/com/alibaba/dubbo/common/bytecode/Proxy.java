/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.common.bytecode;

import com.alibaba.dubbo.common.utils.ClassHelper;
import com.alibaba.dubbo.common.utils.ReflectUtils;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Proxy.
 */

public abstract class Proxy {
    public static final InvocationHandler RETURN_NULL_INVOKER = new InvocationHandler() {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return null;
        }
    };
    public static final InvocationHandler THROW_UNSUPPORTED_INVOKER = new InvocationHandler() {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            throw new UnsupportedOperationException("Method [" + ReflectUtils.getName(method) + "] unimplemented.");
        }
    };
    private static final AtomicLong PROXY_CLASS_COUNTER = new AtomicLong(0);
    private static final String PACKAGE_NAME = Proxy.class.getPackage().getName();
    private static final Map<ClassLoader, Map<String, Object>> ProxyCacheMap = new WeakHashMap<ClassLoader, Map<String, Object>>();

    private static final Object PendingGenerationMarker = new Object();

    protected Proxy() {
    }

    /**
     * Get proxy.
     *
     * @param ics interface class array.
     * @return Proxy instance.
     */
    public static Proxy getProxy(Class<?>... ics) {
        return getProxy(ClassHelper.getClassLoader(Proxy.class), ics);
    }

    /**
     * Get proxy.
     *
     * @param cl  class loader.
     * @param ics interface class array.
     * @return Proxy instance.
     */
    public static Proxy getProxy(ClassLoader cl, Class<?>... ics) {
        //实现的接口个数限制
        if (ics.length > 65535)
            throw new IllegalArgumentException("interface limit exceeded");

        StringBuilder sb = new StringBuilder();
        //校验ics参数
        for (int i = 0; i < ics.length; i++) {
            String itf = ics[i].getName();
            //是否是一个接口
            if (!ics[i].isInterface())
                throw new RuntimeException(itf + " is not a interface.");

            Class<?> tmp = null;
            try {
                //用cl参数传递的类加载器加载接口，用来判断是否能被类加载器加载到
                tmp = Class.forName(itf, false, cl);
            } catch (ClassNotFoundException e) {
            }
            //类加载器加载的接口class和传递进来的不同，或者tmp为null代表加载不到接口class
            if (tmp != ics[i])
                throw new IllegalArgumentException(ics[i] + " is not visible from class loader");

            sb.append(itf).append(';');
        }

        // use interface class name list as key.
        //所有接口的全限定名作为key
        String key = sb.toString();

        // get cache by class loader.
        //根据ClassLoader获取缓存map
        Map<String, Object> cache;
        synchronized (ProxyCacheMap) {
            cache = ProxyCacheMap.get(cl);
            if (cache == null) {
                cache = new HashMap<String, Object>();
                ProxyCacheMap.put(cl, cache);
            }
        }

        Proxy proxy = null;
        synchronized (cache) {
            do {
                //根据key从缓存中获取已经生成的代理实例
                Object value = cache.get(key);
                if (value instanceof Reference<?>) {
                    proxy = (Proxy) ((Reference<?>) value).get();
                    if (proxy != null)
                        return proxy;
                }
                if (value == PendingGenerationMarker) {
                    //如果从缓存中获取到的是PendingGenerationMarker，表示其它线程正在生成dialing，wait并释放锁
                    //锁对象是cache，当一个proxy创建完成后唤醒等待线程，被唤醒的线程不一定等待的是这个proxy。可能不太优雅？
                    try {
                        cache.wait();
                    } catch (InterruptedException e) {
                    }
                } else {
                    //在缓存中放置标识对象PendingGenerationMarker，然后跳出循环
                    cache.put(key, PendingGenerationMarker);
                    break;
                }
            }
            while (true);
        }

        long id = PROXY_CLASS_COUNTER.getAndIncrement();
        String pkg = null;
        //ccp生成的是服务接口的代理，封装了invoker的RPC调用操作，使RPC调用对使用者透明
        //ccm生成的是抽象类com.alibaba.dubbo.common.bytecode.Proxy的子类，实现了抽象方法newInstance
        ClassGenerator ccp = null, ccm = null;
        try {
            ccp = ClassGenerator.newInstance(cl);

            Set<String> worked = new HashSet<String>();
            List<Method> methods = new ArrayList<Method>();

            for (int i = 0; i < ics.length; i++) {
                //如果接口不是public的，则判断接口是否在同一个包
                if (!Modifier.isPublic(ics[i].getModifiers())) {
                    String npkg = ics[i].getPackage().getName();
                    if (pkg == null) {
                        pkg = npkg;
                    } else {
                        if (!pkg.equals(npkg))
                            throw new IllegalArgumentException("non-public interfaces from different packages");
                    }
                }
                ccp.addInterface(ics[i]);

                for (Method method : ics[i].getMethods()) {
                    //获取方法描述，可以理解为方法签名
                    String desc = ReflectUtils.getDesc(method);
                    //如果存在相同的方法签名则跳过，用于多个接口有相同的方法的情况
                    if (worked.contains(desc))
                        continue;
                    //将方法签名放到集合中
                    worked.add(desc);

                    int ix = methods.size();
                    Class<?> rt = method.getReturnType();
                    Class<?>[] pts = method.getParameterTypes();
                    //方法中的代码，Object[] args = new Object[${pts.length}];
                    StringBuilder code = new StringBuilder("Object[] args = new Object[").append(pts.length).append("];");
                    //用方法的参数填充args数组
                    for (int j = 0; j < pts.length; j++)
                        code.append(" args[").append(j).append("] = ($w)$").append(j + 1).append(";");
                    //生成调用handler.invoke方法的代码
                    code.append(" Object ret = handler.invoke(this, methods[" + ix + "], args);");
                    //如果有返回值的话生成return语句
                    if (!Void.TYPE.equals(rt))
                        code.append(" return ").append(asArgument(rt, "ret")).append(";");
                    //添加method到methods集合
                    methods.add(method);
                    //添加方法
                    ccp.addMethod(method.getName(), method.getModifiers(), rt, pts, method.getExceptionTypes(), code.toString());
                }
            }

            if (pkg == null)
                pkg = PACKAGE_NAME;

            // create ProxyInstance class.
            //服务接口代理类的全限定名
            String pcn = pkg + ".proxy" + id;
            ccp.setClassName(pcn);
            //添加static字段methods
            ccp.addField("public static java.lang.reflect.Method[] methods;");
            //添加字段 private java.lang.reflect.InvocationHandler handler;
            ccp.addField("private " + InvocationHandler.class.getName() + " handler;");
            //添加public构造器，接收一个InvocationHandler参数，并将参数赋值给上面handler字段
            ccp.addConstructor(Modifier.PUBLIC, new Class<?>[]{InvocationHandler.class}, new Class<?>[0], "handler=$1;");
            //添加默认构造器
            ccp.addDefaultConstructor();
            //生成服务接口代理类的Class
            Class<?> clazz = ccp.toClass();
            //给静态字段methods赋值，所以set方法的第一个参数为null
            clazz.getField("methods").set(null, methods.toArray(new Method[0]));

            //创建抽象类com.alibaba.dubbo.common.bytecode.Proxy的子类，实现抽象方法newInstance
            String fcn = Proxy.class.getName() + id;
            ccm = ClassGenerator.newInstance(cl);
            ccm.setClassName(fcn);
            ccm.addDefaultConstructor();
            ccm.setSuperClass(Proxy.class);
            //实现抽象方法newInstance，创建ccp生成的服务接口代理类并返回
            ccm.addMethod("public Object newInstance(" + InvocationHandler.class.getName() + " h){ return new " + pcn + "($1); }");
            //生成Proxy子类的Class
            Class<?> pc = ccm.toClass();
            //实例化Proxy子类
            proxy = (Proxy) pc.newInstance();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            // release ClassGenerator
            if (ccp != null)
                ccp.release();
            if (ccm != null)
                ccm.release();
            //将生成的Proxy子类放入缓存
            synchronized (cache) {
                //如果proxy=null，说明创建失败了，则只删除缓存中的PendingGenerationMarker标识
                if (proxy == null)
                    cache.remove(key);
                else
                    //将弱引用的proxy放入缓存，当gc的时候没有强引用指向proxy，则回收这个proxy
                    cache.put(key, new WeakReference<Proxy>(proxy));
                //唤醒wait的线程
                cache.notifyAll();
            }
        }
        return proxy;
    }

    private static String asArgument(Class<?> cl, String name) {
        if (cl.isPrimitive()) {
            if (Boolean.TYPE == cl)
                return name + "==null?false:((Boolean)" + name + ").booleanValue()";
            if (Byte.TYPE == cl)
                return name + "==null?(byte)0:((Byte)" + name + ").byteValue()";
            if (Character.TYPE == cl)
                return name + "==null?(char)0:((Character)" + name + ").charValue()";
            if (Double.TYPE == cl)
                return name + "==null?(double)0:((Double)" + name + ").doubleValue()";
            if (Float.TYPE == cl)
                return name + "==null?(float)0:((Float)" + name + ").floatValue()";
            if (Integer.TYPE == cl)
                return name + "==null?(int)0:((Integer)" + name + ").intValue()";
            if (Long.TYPE == cl)
                return name + "==null?(long)0:((Long)" + name + ").longValue()";
            if (Short.TYPE == cl)
                return name + "==null?(short)0:((Short)" + name + ").shortValue()";
            throw new RuntimeException(name + " is unknown primitive type.");
        }
        return "(" + ReflectUtils.getName(cl) + ")" + name;
    }

    /**
     * get instance with default handler.
     *
     * @return instance.
     */
    public Object newInstance() {
        return newInstance(THROW_UNSUPPORTED_INVOKER);
    }

    /**
     * get instance with special handler.
     *
     * @return instance.
     */
    abstract public Object newInstance(InvocationHandler handler);
}
