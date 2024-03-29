// Copyright (C) 2009 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.httpd;

import static com.google.inject.Scopes.SINGLETON;
import static com.google.inject.Stage.PRODUCTION;

import com.google.gerrit.common.ChangeHookRunner;
import com.google.gerrit.httpd.auth.openid.OpenIdModule;
import com.google.gerrit.httpd.plugins.HttpPluginModule;
import com.google.gerrit.lifecycle.LifecycleManager;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.lucene.LuceneIndexModule;
import com.google.gerrit.reviewdb.client.AuthType;
import com.google.gerrit.server.account.InternalAccountDirectory;
import com.google.gerrit.server.cache.h2.DefaultCacheFactory;
import com.google.gerrit.server.config.AuthConfig;
import com.google.gerrit.server.config.AuthConfigModule;
import com.google.gerrit.server.config.CanonicalWebUrlModule;
import com.google.gerrit.server.config.GerritGlobalModule;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.GerritServerConfigModule;
import com.google.gerrit.server.config.MasterNodeStartup;
import com.google.gerrit.server.config.SitePath;
import com.google.gerrit.server.contact.HttpContactStoreConnection;
import com.google.gerrit.server.git.LocalDiskRepositoryManager;
import com.google.gerrit.server.git.ReceiveCommitsExecutorModule;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.index.IndexModule;
import com.google.gerrit.server.index.NoIndexModule;
import com.google.gerrit.server.mail.SignedTokenEmailTokenVerifier;
import com.google.gerrit.server.mail.SmtpEmailSender;
import com.google.gerrit.server.patch.IntraLineWorkerPool;
import com.google.gerrit.server.plugins.PluginGuiceEnvironment;
import com.google.gerrit.server.plugins.PluginRestApiModule;
import com.google.gerrit.server.schema.DataSourceModule;
import com.google.gerrit.server.schema.DataSourceProvider;
import com.google.gerrit.server.schema.DataSourceType;
import com.google.gerrit.server.schema.DatabaseModule;
import com.google.gerrit.server.schema.SchemaModule;
import com.google.gerrit.server.schema.SchemaVersionCheck;
import com.google.gerrit.solr.SolrIndexModule;
import com.google.gerrit.sshd.SshKeyCacheImpl;
import com.google.gerrit.sshd.SshModule;
import com.google.gerrit.sshd.commands.MasterCommandModule;
import com.google.inject.AbstractModule;
import com.google.inject.CreationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.name.Names;
import com.google.inject.servlet.GuiceFilter;
import com.google.inject.servlet.GuiceServletContextListener;
import com.google.inject.spi.Message;

import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.sql.DataSource;

/** Configures the web application environment for Gerrit Code Review. */
public class WebAppInitializer extends GuiceServletContextListener
    implements Filter {
  private static final Logger log =
      LoggerFactory.getLogger(WebAppInitializer.class);

  private File sitePath;
  private Injector dbInjector;
  private Injector cfgInjector;
  private Injector sysInjector;
  private Injector webInjector;
  private Injector sshInjector;
  private LifecycleManager manager;
  private GuiceFilter filter;

  @Override
  public void doFilter(ServletRequest req, ServletResponse res,
      FilterChain chain) throws IOException, ServletException {
    filter.doFilter(req, res, chain);
  }

  private synchronized void init() {
    if (manager == null) {
      final String path = System.getProperty("gerrit.site_path");
      if (path != null) {
        sitePath = new File(path);
      }

      if (System.getProperty("gerrit.init") != null) {
        new SiteInitializer(path, System.getProperty("gerrit.init_path")).init();
      }

      try {
        dbInjector = createDbInjector();
      } catch (CreationException ce) {
        final Message first = ce.getErrorMessages().iterator().next();
        final StringBuilder buf = new StringBuilder();
        buf.append(first.getMessage());
        Throwable why = first.getCause();
        while (why != null) {
          buf.append("\n  caused by ");
          buf.append(why.toString());
          why = why.getCause();
        }
        if (first.getCause() != null) {
          buf.append("\n");
          buf.append("\nResolve above errors before continuing.");
          buf.append("\nComplete stack trace follows:");
        }
        log.error(buf.toString(), first.getCause());
        throw new CreationException(Collections.singleton(first));
      }

      cfgInjector = createCfgInjector();
      sysInjector = createSysInjector();
      sshInjector = createSshInjector();
      webInjector = createWebInjector();

      PluginGuiceEnvironment env = sysInjector.getInstance(PluginGuiceEnvironment.class);
      env.setCfgInjector(cfgInjector);
      env.setSshInjector(sshInjector);
      env.setHttpInjector(webInjector);

      // Push the Provider<HttpServletRequest> down into the canonical
      // URL provider. Its optional for that provider, but since we can
      // supply one we should do so, in case the administrator has not
      // setup the canonical URL in the configuration file.
      //
      // Note we have to do this manually as Guice failed to do the
      // injection here because the HTTP environment is not visible
      // to the core server modules.
      //
      sysInjector.getInstance(HttpCanonicalWebUrlProvider.class)
          .setHttpServletRequest(
              webInjector.getProvider(HttpServletRequest.class));

      filter = webInjector.getInstance(GuiceFilter.class);
      manager = new LifecycleManager();
      manager.add(dbInjector);
      manager.add(cfgInjector);
      manager.add(sysInjector);
      manager.add(sshInjector);
      manager.add(webInjector);
    }
  }

  private Injector createDbInjector() {
    final List<Module> modules = new ArrayList<Module>();
    if (sitePath != null) {
      Module sitePathModule = new AbstractModule() {
        @Override
        protected void configure() {
          bind(File.class).annotatedWith(SitePath.class).toInstance(sitePath);
        }
      };
      modules.add(sitePathModule);

      Module configModule = new GerritServerConfigModule();
      modules.add(configModule);

      Injector cfgInjector = Guice.createInjector(sitePathModule, configModule);
      Config cfg = cfgInjector.getInstance(Key.get(Config.class,
          GerritServerConfig.class));
      String dbType = cfg.getString("database", null, "type");

      final DataSourceType dst = Guice.createInjector(new DataSourceModule(),
          configModule, sitePathModule).getInstance(
            Key.get(DataSourceType.class, Names.named(dbType.toLowerCase())));
      modules.add(new AbstractModule() {
        @Override
        protected void configure() {
          bind(DataSourceType.class).toInstance(dst);
        }
      });

      modules.add(new LifecycleModule() {
        @Override
        protected void configure() {
          bind(DataSourceProvider.Context.class).toInstance(
              DataSourceProvider.Context.MULTI_USER);
          bind(Key.get(DataSource.class, Names.named("ReviewDb"))).toProvider(
              DataSourceProvider.class).in(SINGLETON);
          listener().to(DataSourceProvider.class);
        }
      });

    } else {
      modules.add(new LifecycleModule() {
        @Override
        protected void configure() {
          bind(Key.get(DataSource.class, Names.named("ReviewDb"))).toProvider(
              ReviewDbDataSourceProvider.class).in(SINGLETON);
          listener().to(ReviewDbDataSourceProvider.class);
        }
      });
    }
    modules.add(new DatabaseModule());
    return Guice.createInjector(PRODUCTION, modules);
  }

  private Injector createCfgInjector() {
    final List<Module> modules = new ArrayList<Module>();
    if (sitePath == null) {
      // If we didn't get the site path from the system property
      // we need to get it from the database, as that's our old
      // method of locating the site path on disk.
      //
      modules.add(new AbstractModule() {
        @Override
        protected void configure() {
          bind(File.class).annotatedWith(SitePath.class).toProvider(
              SitePathFromSystemConfigProvider.class).in(SINGLETON);
        }
      });
      modules.add(new GerritServerConfigModule());
    }
    modules.add(new SchemaModule());
    modules.add(new LocalDiskRepositoryManager.Module());
    modules.add(SchemaVersionCheck.module());
    modules.add(new AuthConfigModule());
    return dbInjector.createChildInjector(modules);
  }

  private Injector createSysInjector() {
    final List<Module> modules = new ArrayList<Module>();
    modules.add(new WorkQueue.Module());
    modules.add(new ChangeHookRunner.Module());
    modules.add(new ReceiveCommitsExecutorModule());
    modules.add(new IntraLineWorkerPool.Module());
    modules.add(cfgInjector.getInstance(GerritGlobalModule.class));
    modules.add(new InternalAccountDirectory.Module());
    modules.add(new DefaultCacheFactory.Module());
    modules.add(new SmtpEmailSender.Module());
    modules.add(new SignedTokenEmailTokenVerifier.Module());
    modules.add(new PluginRestApiModule());
    AbstractModule changeIndexModule;
    switch (IndexModule.getIndexType(cfgInjector)) {
      case LUCENE:
        changeIndexModule = new LuceneIndexModule();
        break;
      case SOLR:
        changeIndexModule = new SolrIndexModule();
        break;
      default:
        changeIndexModule = new NoIndexModule();
    }
    modules.add(changeIndexModule);
    modules.add(new CanonicalWebUrlModule() {
      @Override
      protected Class<? extends Provider<String>> provider() {
        return HttpCanonicalWebUrlProvider.class;
      }
    });
    modules.add(SshKeyCacheImpl.module());
    modules.add(new MasterNodeStartup());
    modules.add(new AbstractModule() {
      @Override
      protected void configure() {
        bind(GerritUiOptions.class).toInstance(new GerritUiOptions(false));
      }
    });
    return cfgInjector.createChildInjector(modules);
  }

  private Injector createSshInjector() {
    final List<Module> modules = new ArrayList<Module>();
    modules.add(sysInjector.getInstance(SshModule.class));
    modules.add(new MasterCommandModule());
    return sysInjector.createChildInjector(modules);
  }

  private Injector createWebInjector() {
    final List<Module> modules = new ArrayList<Module>();
    modules.add(RequestContextFilter.module());
    modules.add(AllRequestFilter.module());
    modules.add(sysInjector.getInstance(GitOverHttpModule.class));
    modules.add(sshInjector.getInstance(WebModule.class));
    modules.add(sshInjector.getInstance(WebSshGlueModule.class));
    modules.add(CacheBasedWebSession.module());
    modules.add(HttpContactStoreConnection.module());
    modules.add(new HttpPluginModule());

    AuthConfig authConfig = cfgInjector.getInstance(AuthConfig.class);
    if (authConfig.getAuthType() == AuthType.OPENID) {
      modules.add(new OpenIdModule());
    }

    return sysInjector.createChildInjector(modules);
  }

  @Override
  protected Injector getInjector() {
    init();
    return webInjector;
  }

  @Override
  public void init(FilterConfig cfg) throws ServletException {
    contextInitialized(new ServletContextEvent(cfg.getServletContext()));
    init();
    manager.start();
  }

  @Override
  public void destroy() {
    if (manager != null) {
      manager.stop();
      manager = null;
    }
  }
}
