package io.github.rodrigorjsf.agenticchat.infra;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The executor sub-agent workflows fan out on.
 *
 * <p>Virtual threads, one per task: a sub-agent spends its time blocked on an HTTP
 * call to a model or a public API, which is exactly the workload virtual threads
 * exist for. There is no pool size to tune because there is no pool — the bound on
 * concurrency is the number of sub-agents a workflow declares, not the number of
 * carrier threads.
 *
 * <p>Its own executor rather than the common pool: a workflow that blocks on two
 * public APIs has no business occupying threads shared with everything else in the
 * JVM.
 */
@Factory
public class WorkflowExecutorFactory {

    @Singleton
    @Named("agentic-workflow")
    @Bean(preDestroy = "shutdown")
    ExecutorService workflowExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
