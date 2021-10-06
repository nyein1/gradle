/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.work;

import com.google.common.base.Supplier;
import org.gradle.internal.UncheckedException;

class DefaultSynchronizer implements Synchronizer {
    private final WorkerLeaseService workerLeaseService;
    private Thread owner;

    public DefaultSynchronizer(WorkerLeaseService workerLeaseService) {
        this.workerLeaseService = workerLeaseService;
    }

    @Override
    public void withLock(Runnable action) {
        takeOwnership();
        try {
            action.run();
        } finally {
            releaseOwnership();
        }
    }

    @Override
    public <T> T withLock(Supplier<T> action) {
        takeOwnership();
        try {
            return action.get();
        } finally {
            releaseOwnership();
        }
    }

    private void takeOwnership() {
        final Thread currentThread = Thread.currentThread();
        if (!workerLeaseService.isWorkerThread()) {
            throw new IllegalStateException("The current thread is not registered as a worker thread.");
        }
        synchronized (this) {
            if (owner == null) {
                owner = currentThread;
                return;
            } else if (owner == currentThread) {
                throw new UnsupportedOperationException("The current thread already holds this lock.");
            }
        }
        workerLeaseService.blocking(new Runnable() {
            @Override
            public void run() {
                synchronized (this) {
                    while (owner != null) {
                        try {
                            this.wait();
                        } catch (InterruptedException e) {
                            throw UncheckedException.throwAsUncheckedException(e);
                        }
                    }
                    owner = currentThread;
                }
            }
        });
    }

    private void releaseOwnership() {
        synchronized (this) {
            owner = null;
            this.notifyAll();
        }
    }
}
