package com.example.infra;

import com.google.cloud.bigquery.Acl;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;

public class TestAcl {
    @Test
    public void testAclReflection() throws Exception {
        Class<?>[] classes = Acl.class.getDeclaredClasses();
        for (Class<?> c : classes) {
            System.out.println("Class: " + c.getName());
            for (Method m : c.getDeclaredMethods()) {
                System.out.println("  Method: " + m.getName());
            }
        }
    }
}
