package com.github.prepushchecker;

import com.intellij.compiler.impl.ProjectCompileScope;
import com.intellij.compiler.server.BuildManagerListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompilerTopics;
import com.intellij.openapi.compiler.DummyCompileContext;
import com.intellij.openapi.module.Module;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SmartBuildServiceTest extends BasePlatformTestCase {
    public void testAutomakeOnEdtReadsAffectedModulesInBackground() throws Exception {
        assertBackgroundScopeRead(true);
    }

    public void testCompilationFinishedOnEdtReadsAffectedModulesInBackground() throws Exception {
        assertBackgroundScopeRead(false);
    }

    private void assertBackgroundScopeRead(boolean automake) throws Exception {
        var app = ApplicationManager.getApplication();
        assertTrue(app.isDispatchThread());
        SmartBuildService.getInstance(getProject()).start();

        var backgroundRead = new CompletableFuture<Boolean>();
        CompileScope scope = new ProjectCompileScope(getProject()) {
            @Override
            public Module[] getAffectedModules() {
                backgroundRead.complete(!app.isDispatchThread() && app.isReadAccessAllowed());
                return Module.EMPTY_ARRAY;
            }
        };
        var context = new DummyCompileContext(getProject()) {
            @Override
            public CompileScope getCompileScope() {
                return scope;
            }
        };
        app.getMessageBus().syncPublisher(BuildManagerListener.TOPIC)
            .buildStarted(getProject(), UUID.randomUUID(), automake);
        var publisher = getProject().getMessageBus().syncPublisher(CompilerTopics.COMPILATION_STATUS);
        if (automake) {
            publisher.automakeCompilationFinished(0, 0, context);
        } else {
            publisher.compilationFinished(false, 0, 0, context);
        }

        assertTrue("Expected affected modules to be read under a background read lock",
            PlatformTestUtil.waitForFuture(backgroundRead, 10_000));
    }
}
