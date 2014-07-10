/*
 * Copyright 2013 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kie.workbench.common.services.backend.builder;

import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;

import org.guvnor.common.services.builder.*;
import org.guvnor.common.services.project.builder.model.BuildResults;
import org.guvnor.common.services.project.builder.service.BuildService;
import org.guvnor.common.services.project.model.Project;
import org.guvnor.common.services.project.service.ProjectService;
import org.guvnor.structure.server.config.ConfigGroup;
import org.guvnor.structure.server.config.ConfigType;
import org.guvnor.structure.server.config.ConfigurationFactory;
import org.guvnor.structure.server.config.ConfigurationService;
import org.jboss.weld.environment.se.StartMain;
import org.junit.Before;
import org.junit.Test;
import org.uberfire.backend.server.util.Paths;
import org.uberfire.backend.vfs.Path;
import org.uberfire.java.nio.fs.file.SimpleFileSystemProvider;

import static org.junit.Assert.*;

public class ResourceChangeIncrementalBuilderConcurrencyTest {

    private static final String GLOBAL_SETTINGS = "settings";

    private final SimpleFileSystemProvider fs = new SimpleFileSystemProvider();
    private BeanManager beanManager;

    private Paths paths;
    private ConfigurationService configurationService;
    private ConfigurationFactory configurationFactory;
    private BuildResultsObserver buildResultsObserver;
    private BuildService buildService;
    private ProjectService projectService;

    @Before
    public void setUp() throws Exception {
        //Bootstrap WELD container
        StartMain startMain = new StartMain( new String[ 0 ] );
        beanManager = startMain.go().getBeanManager();

        //Instantiate Paths used in tests for Path conversion
        final Bean pathsBean = (Bean) beanManager.getBeans( Paths.class ).iterator().next();
        final CreationalContext cc1 = beanManager.createCreationalContext( pathsBean );
        paths = (Paths) beanManager.getReference( pathsBean,
                                                  Paths.class,
                                                  cc1 );

        //Instantiate ConfigurationService
        final Bean configurationServiceBean = (Bean) beanManager.getBeans( ConfigurationService.class ).iterator().next();
        final CreationalContext cc2 = beanManager.createCreationalContext( configurationServiceBean );
        configurationService = (ConfigurationService) beanManager.getReference( configurationServiceBean,
                                                                                ConfigurationService.class,
                                                                                cc2 );

        //Instantiate ConfigurationFactory
        final Bean configurationFactoryBean = (Bean) beanManager.getBeans( ConfigurationFactory.class ).iterator().next();
        final CreationalContext cc3 = beanManager.createCreationalContext( configurationFactoryBean );
        configurationFactory = (ConfigurationFactory) beanManager.getReference( configurationFactoryBean,
                                                                                ConfigurationFactory.class,
                                                                                cc3 );

        //Instantiate BuildResultsObserver
        final Bean buildResultsObserverBean = (Bean) beanManager.getBeans( BuildResultsObserver.class ).iterator().next();
        final CreationalContext cc4 = beanManager.createCreationalContext( buildResultsObserverBean );
        buildResultsObserver = (BuildResultsObserver) beanManager.getReference( buildResultsObserverBean,
                                                                                BuildResultsObserver.class,
                                                                                cc4 );

        //Instantiate BuildService
        final Bean buildServiceBean = (Bean) beanManager.getBeans( BuildService.class ).iterator().next();
        final CreationalContext cc5 = beanManager.createCreationalContext( buildServiceBean );
        buildService = (BuildService) beanManager.getReference( buildServiceBean,
                                                                BuildService.class,
                                                                cc5 );

        //Instantiate ProjectService
        final Bean projectServiceBean = (Bean) beanManager.getBeans( ProjectService.class ).iterator().next();
        final CreationalContext cc6 = beanManager.createCreationalContext( projectServiceBean );
        projectService = (ProjectService) beanManager.getReference( projectServiceBean,
                                                                    ProjectService.class,
                                                                    cc6 );

        //Define mandatory properties
        List<ConfigGroup> globalConfigGroups = configurationService.getConfiguration( ConfigType.GLOBAL );
        boolean globalSettingsDefined = false;
        for ( ConfigGroup globalConfigGroup : globalConfigGroups ) {
            if ( GLOBAL_SETTINGS.equals( globalConfigGroup.getName() ) ) {
                globalSettingsDefined = true;
                break;
            }
        }
        if ( !globalSettingsDefined ) {
            configurationService.addConfiguration( getGlobalConfiguration() );
        }

    }

    private ConfigGroup getGlobalConfiguration() {
        //Global Configurations used by many of Drools Workbench editors
        final ConfigGroup group = configurationFactory.newConfigGroup( ConfigType.GLOBAL,
                                                                       GLOBAL_SETTINGS,
                                                                       "" );
        group.addConfigItem( configurationFactory.newConfigItem( "build.enable-incremental",
                                                                 "true" ) );
        return group;
    }

    @Test
    public void testConcurrentResourceUpdates() throws URISyntaxException {
        final Bean buildChangeListenerBean = (Bean) beanManager.getBeans( org.guvnor.common.services.builder.ResourceChangeIncrementalBuilder.class ).iterator().next();
        final CreationalContext cc = beanManager.createCreationalContext( buildChangeListenerBean );
        final org.guvnor.common.services.builder.ResourceChangeIncrementalBuilder buildChangeListener = (org.guvnor.common.services.builder.ResourceChangeIncrementalBuilder) beanManager.getReference( buildChangeListenerBean,
                                                                                                                                  org.guvnor.common.services.builder.ResourceChangeIncrementalBuilder.class,
                                                                                                                                  cc );

        final URL resourceUrl = this.getClass().getResource( "/BuildChangeListenerRepo/src/main/resources/update.drl" );
        final org.uberfire.java.nio.file.Path nioResourcePath = fs.getPath( resourceUrl.toURI() );
        final Path resourcePath = paths.convert( nioResourcePath );

        //Force full build before attempting incremental changes
        final Project project = projectService.resolveProject( resourcePath );
        final BuildResults buildResults = buildService.build( project );
        assertNotNull( buildResults );
        assertEquals( 0,
                      buildResults.getMessages().size() );

        //Perform incremental build
        final int THREADS = 200;
        final Result result = new Result();
        ExecutorService es = Executors.newCachedThreadPool();
        for ( int i = 0; i < THREADS; i++ ) {
            es.execute( new Runnable() {
                @Override
                public void run() {
                    try {
                        System.out.println( "Thread " + Thread.currentThread().getName() + " has started for " + resourcePath.toURI() );
                        buildChangeListener.updateResource( resourcePath );
                        System.out.println( "Thread " + Thread.currentThread().getName() + " has completed for " + resourcePath.toURI() );
                    } catch ( Throwable e ) {
                        result.setFailed( true );
                        result.setMessage( e.getMessage() );
                        System.out.println( e.getMessage() );
                    }
                }
            } );
        }

        es.shutdown();
        try {
            es.awaitTermination( 5,
                                 TimeUnit.MINUTES );
        } catch ( InterruptedException e ) {
        }
        if ( result.isFailed() ) {
            fail( result.getMessage() );
        }
    }

    private static class Result {

        private boolean failed = false;
        private String message = "";

        public synchronized boolean isFailed() {
            return failed;
        }

        public synchronized void setFailed( boolean failed ) {
            this.failed = failed;
        }

        public synchronized String getMessage() {
            return message;
        }

        public synchronized void setMessage( String message ) {
            this.message = message;
        }
    }

}
