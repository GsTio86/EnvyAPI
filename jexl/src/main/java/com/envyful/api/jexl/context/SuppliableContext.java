package com.envyful.api.jexl.context;

import com.google.common.collect.Maps;
import org.apache.commons.jexl3.JexlContext;

import java.util.Map;
import java.util.function.Supplier;

public class SuppliableContext implements JexlContext {

    private final Map<String, Object> context = Maps.newHashMap();

    public SuppliableContext() {
    }

    @Override
    public Object get(String s) {
        var object = this.context.get(s);

        if (object instanceof Supplier) {
            return ((Supplier<?>) object).get();
        }

        return object;
    }

    @Override
    public void set(String s, Object o) {
        this.context.put(s, o);
    }

    @Override
    public boolean has(String s) {
        return this.context.containsKey(s);
    }
}
