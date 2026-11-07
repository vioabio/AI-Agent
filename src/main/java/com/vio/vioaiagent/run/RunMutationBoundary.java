package com.vio.vioaiagent.run;

import java.util.function.Supplier;

/** Only the Run worker may grant a bounded local mutation; standalone contexts deny it. */
public interface RunMutationBoundary {
    <T> T write(Supplier<T> action);
    void check();
    static RunMutationBoundary denied() {
        return new RunMutationBoundary() {
            public <T> T write(Supplier<T> action) { check(); return null; }
            public void check() { throw new IllegalStateException("缺少受控 Run 写入边界"); }
        };
    }
}
