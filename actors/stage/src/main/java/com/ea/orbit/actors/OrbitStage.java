/*
Copyright (C) 2015 Electronic Arts Inc.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

1.  Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
2.  Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.
3.  Neither the name of Electronic Arts, Inc. ("EA") nor the names of
    its contributors may be used to endorse or promote products derived
    from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY ELECTRONIC ARTS AND ITS CONTRIBUTORS "AS IS" AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL ELECTRONIC ARTS OR ITS CONTRIBUTORS BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package com.ea.orbit.actors;


import com.ea.orbit.actors.annotation.NoIdentity;
import com.ea.orbit.actors.cluster.ClusterPeer;
import com.ea.orbit.actors.cluster.IClusterPeer;
import com.ea.orbit.actors.providers.ILifetimeProvider;
import com.ea.orbit.actors.providers.IOrbitProvider;
import com.ea.orbit.actors.runtime.Execution;
import com.ea.orbit.actors.runtime.Hosting;
import com.ea.orbit.actors.runtime.IHosting;
import com.ea.orbit.actors.runtime.Messaging;
import com.ea.orbit.actors.runtime.OrbitActor;
import com.ea.orbit.annotation.Config;
import com.ea.orbit.concurrent.Task;
import com.ea.orbit.container.OrbitContainer;
import com.ea.orbit.container.Startable;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Singleton
public class OrbitStage implements Startable
{
    @Config("orbit.actors.clusterName")
    private String clusterName;

    @Config("orbit.actors.stageMode")
    private StageMode mode = StageMode.HOST;

    @Config("orbit.actors.providers")
    private List<Object> providers = new ArrayList<>();


    private IClusterPeer clusterPeer;
    private Task startFuture;
    private Messaging messaging;
    private Execution execution;
    private Hosting hosting;
    private boolean startCalled;
    private Clock clock;
    private ExecutorService executionPool;
    private ExecutorService messagingPool;

    @Inject
    OrbitContainer orbitContainer;  // Only injected if running on Orbit container
    private boolean autoDiscovery;

    public void setClock(final Clock clock)
    {
        this.clock = clock;
    }

    public void setExecutionPool(final ExecutorService executionPool)
    {
        this.executionPool = executionPool;
    }

    public ExecutorService getExecutionPool()
    {
        return executionPool;
    }

    public void setMessagingPool(final ExecutorService messagingPool)
    {
        this.messagingPool = messagingPool;
    }

    public ExecutorService getMessagingPool()
    {
        return messagingPool;
    }

    public void setAutoDiscovery(boolean autoDiscovery)
    {
        this.autoDiscovery = autoDiscovery;
    }

    public enum StageMode
    {
        FRONT_END, // no activations
        HOST // allows activations
    }

    public String getClusterName()
    {
        return clusterName;
    }

    public void setClusterName(final String clusterName)
    {
        this.clusterName = clusterName;
    }

    public StageMode getMode()
    {
        return mode;
    }

    public void setMode(final StageMode mode)
    {
        if (startCalled)
        {
            throw new IllegalStateException("Stage mode cannot be changed after startup.");
        }
        this.mode = mode;
    }

    public Task start()
    {
        startCalled = true;

        if (hosting == null)
        {
            hosting = new Hosting();
        }
        if (messaging == null)
        {
            messaging = new Messaging();
        }
        if (execution == null)
        {
            execution = new Execution();
        }
        if (clusterPeer == null)
        {
            clusterPeer = new ClusterPeer();
        }
        if (clock == null)
        {
            clock = Clock.systemUTC();
        }

        this.wireOrbitContainer();

        hosting.setNodeType(mode == StageMode.HOST ? IHosting.NodeTypeEnum.SERVER : IHosting.NodeTypeEnum.CLIENT);
        execution.setClock(clock);
        execution.setHosting(hosting);
        execution.setMessaging(messaging);
        execution.setExecutor(executionPool);

        messaging.setExecution(execution);
        messaging.setClock(clock);
        messaging.setExecutor(messagingPool);

        hosting.setExecution(execution);
        hosting.setClusterPeer(clusterPeer);
        messaging.setClusterPeer(clusterPeer);

        execution.setAutoDiscovery(autoDiscovery);

        execution.setOrbitProviders(providers.stream()
                .filter(p -> p instanceof IOrbitProvider)
                .map(p -> (IOrbitProvider) p)
                .collect(Collectors.toList()));
        execution.setActorClassPatterns(providers.stream()
                .filter(p -> p instanceof String)
                .map(p -> (String) p)
                .map(s -> Pattern.compile(s))
                .collect(Collectors.toList()));
        execution.setActorClasses(providers.stream()
                .filter(p -> p instanceof Class)
                .map(p -> (Class<?>) p)
                .filter(c -> IActor.class.isAssignableFrom(c)
                        || IActorObserver.class.isAssignableFrom(c))
                .collect(Collectors.toList()));

        messaging.start();
        hosting.start();
        execution.start();
        startFuture = clusterPeer.join(clusterName);
        // todo remove this
        startFuture.join();
        return startFuture;
    }

    private void wireOrbitContainer()
    {
        // orbitContainer will be null if the application is not using it
        if (orbitContainer != null)
        {
            ILifetimeProvider containerLifetime = new ILifetimeProvider()
            {
                @Override
                public Task preActivation(OrbitActor actor)
                {
                    orbitContainer.inject(actor);
                    return Task.done();
                }
            };

            providers.add(containerLifetime);
        }
    }

    @SuppressWarnings({"unsafe", "unchecked"})
    public <T extends IActor> T getReference(final Class<T> iClass, final String id)
    {
        if (iClass.isAnnotationPresent(NoIdentity.class))
        {
            throw new IllegalArgumentException("Cannot be called for classes annotated with @NoIdentity");
        }
        return execution.getReference(iClass, id);
    }

    @SuppressWarnings({"unsafe", "unchecked"})
    public <T extends IActor> T getReference(final Class<T> iClass)
    {
        if (!iClass.isAnnotationPresent(NoIdentity.class))
        {
            throw new IllegalArgumentException("Can only be called for classes annotated with @NoIdentity");
        }
        return execution.getReference(iClass, NoIdentity.NO_IDENTITY);
    }

    public void setClusterPeer(final IClusterPeer clusterPeer)
    {
        this.clusterPeer = clusterPeer;
    }

    /**
     * Installs extensions to the stage: Actors or Providers
     * <p/>
     * Valid arguments:
     * <table style="border: 1px; border-collapse: collapse;">
     * <tr><td>String</td><td>Actor class name pattern</td>
     * <tr><td>Class</td><td>OrbitActor or IActor</td>
     * <tr><td>Object</td><td>Instance of a provider</td>
     * </table>
     * Invalid arguments are ignored.
     * <p/>
     * Examples:
     * <pre>{@code
     * stage.addProvider(HelloActor.class);
     * stage.addProvider("com.ea.orbit.actors.*");
     * stage.addProvider("com.ea.orbit.samples.chat.Chat");
     * stage.addProvider(new MongoDbProvider(...));
     * }</pre>
     *
     * @param provider Actor classNamePattern, Actor class, or provider instance.
     */
    public void addProvider(final Object provider)
    {
        this.providers.add(provider);
    }

    public Task stop()
    {
        return execution.stop()
                .thenRun(clusterPeer::leave);
    }

    public <T extends IActorObserver> T getObserverReference(Class<T> iClass, final T observer)
    {
        return execution.getObjectReference(iClass, observer);
    }

    public <T extends IActorObserver> T getObserverReference(final T observer)
    {
        return execution.getObjectReference(null, observer);
    }

    public Hosting getHosting()
    {
        return hosting;
    }

    public IClusterPeer getClusterPeer()
    {
        return clusterPeer != null ? clusterPeer : (clusterPeer = new ClusterPeer());
    }

    public void cleanup(boolean block)
    {
        execution.activationCleanup(block);
        messaging.timeoutCleanup();
    }

    /**
     * Binds this stage to the current thread.
     * This tells ungrounded references will use this stage to call remote methods.
     * <p/>
     * An ungrounded reference is a reference created with {@code IActor.ref} and used outside of an actor method.
     * <p/>
     * This is only necessary when there are <i>two or more</i> OrbitStages active in the same machine and
     * remote calls need to be issued from outside an actor.
     * This method was created was created to help with test cases.
     * <p/>
     * A normal application will have a single stage an should have no reason to call this method.
     * <p/>
     * This method writes a weak reference to the runtime in a thread local.
     * No cleanup is necessary, so none is available.
     */
    public void bind()
    {
        execution.bind();
    }

    /**
     * Binds this reference to the current stage.
     * This changes this reference so that it will always use this stage to issue remote calls.
     * <p/>
     * This is only necessary when there are <i>two or more</i> OrbitStages active in the same machine and
     * references are created and used from outside an actor.     *
     * This method was created was help with test cases.
     * <p/>
     * A normal application will have a single stage an should have no reason to call this method.
     * <p/>
     * This method writes to an internal variable of the reference binding to itself.
     *
     * @param actorReference the reference to be bound to this stage
     */
    public void bind(IActor actorReference)
    {
        execution.bind(actorReference);
    }

    /**
     * @param actorObserverReference the reference to be bound to this stage
     * @See bind(IActor actor)
     */
    public void bind(IActorObserver actorObserverReference)
    {
        execution.bind(actorObserverReference);
    }

}
