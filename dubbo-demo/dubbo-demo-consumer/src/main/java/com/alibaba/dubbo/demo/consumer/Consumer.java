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
package com.alibaba.dubbo.demo.consumer;

import com.alibaba.dubbo.demo.DemoService;
import com.alibaba.dubbo.demo.TestException;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.proxy.InvokerInvocationHandler;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.lang.reflect.Field;

public class Consumer {

    public static void main(String[] args) {
        //Prevent to get IPV6 address,this way only work in debug mode
        //But you can pass use -Djava.net.preferIPv4Stack=true,then it work well whether in debug mode or not
        System.setProperty("java.net.preferIPv4Stack", "true");
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext(new String[]{"META-INF/spring/dubbo-demo-consumer.xml"});
        context.start();
        DemoService demoService = (DemoService) context.getBean("demoService"); // get remote service proxy

       /*
       //直接使用invoker调用服务提供者
       try {
            Field handler = demoService.getClass().getDeclaredField("handler");
            handler.setAccessible(true);
            InvokerInvocationHandler invokerInvocationHandler = (InvokerInvocationHandler) handler.get(demoService);
            Field invokerField = invokerInvocationHandler.getClass().getDeclaredField("invoker");
            invokerField.setAccessible(true);
            Invoker invoker1 = (Invoker) invokerField.get(invokerInvocationHandler);

            //使用invoker调用服务提供者
            Result result = invoker1.invoke(new RpcInvocation(DemoService.class.getMethod("sayHello", String.class), new String[]{"world"}));
            Object value = result.recreate();
            System.out.println("使用invoker调用服务提供者结果=" + value);
        } catch (Throwable e) {
            e.printStackTrace();
        }*/
        while (true) {
            try {
                Thread.sleep(1000);
                String hello = demoService.sayHello("world"); // call remote method
                System.out.println(hello); // get result

            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }

    }
}
