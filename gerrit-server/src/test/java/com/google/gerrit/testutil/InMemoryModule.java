// Copyright (C) 2013 The Android Open Source Project
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

package com.google.gerrit.testutil;

import static com.google.common.base.Preconditions.checkState;
import static com.google.inject.Scopes.SINGLETON;

import com.google.common.net.InetAddresses;
import com.google.gerrit.common.ChangeHooks;
import com.google.gerrit.common.DisabledChangeHooks;
import com.google.gerrit.reviewdb.client.AuthType;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.GerritPersonIdent;
import com.google.gerrit.server.GerritPersonIdentProvider;
import com.google.gerrit.server.RemotePeer;
import com.google.gerrit.server.cache.h2.DefaultCacheFactory;
import com.google.gerrit.server.change.MergeabilityChecksExecutorModule;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.AllProjectsNameProvider;
import com.google.gerrit.server.config.AnonymousCowardName;
import com.google.gerrit.server.config.AnonymousCowardNameProvider;
import com.google.gerrit.server.config.CanonicalWebUrlModule;
import com.google.gerrit.server.config.CanonicalWebUrlProvider;
import com.google.gerrit.server.config.FactoryModule;
import com.google.gerrit.server.config.GerritGlobalModule;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePath;
import com.google.gerrit.server.config.TrackingFooters;
import com.google.gerrit.server.config.TrackingFootersProvider;
import com.google.gerrit.server.git.EmailReviewCommentsExecutor;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.PerThreadRequestScope;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.index.ChangeSchemas;
import com.google.gerrit.server.index.IndexModule.IndexType;
import com.google.gerrit.server.mail.SignedTokenEmailTokenVerifier;
import com.google.gerrit.server.mail.SmtpEmailSender;
import com.google.gerrit.server.schema.Current;
import com.google.gerrit.server.schema.DataSourceType;
import com.google.gerrit.server.schema.SchemaCreator;
import com.google.gerrit.server.schema.SchemaVersion;
import com.google.gerrit.server.ssh.NoSshKeyCache;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.ProvisionException;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.servlet.RequestScoped;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.PersonIdent;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class InMemoryModule extends FactoryModule {
  public static Config newDefaultConfig() {
    Config cfg = new Config();
    cfg.setEnum("auth", null, "type", AuthType.DEVELOPMENT_BECOME_ANY_ACCOUNT);
    cfg.setString("gerrit", null, "basePath", "git");
    cfg.setString("gerrit", null, "allProjects", "Test-Projects");
    cfg.setString("user", null, "name", "Gerrit Code Review");
    cfg.setString("user", null, "email", "gerrit@localhost");
    cfg.setBoolean("sendemail", null, "enable", false);
    cfg.setString("cache", null, "directory", null);
    cfg.setString("index", null, "type", "lucene");
    cfg.setBoolean("index", "lucene", "testInmemory", true);
    cfg.setInt("index", "lucene", "testVersion",
        ChangeSchemas.getLatest().getVersion());
    return cfg;
  }

  private final Config cfg;

  public InMemoryModule() {
    this(newDefaultConfig());
  }

  public InMemoryModule(Config cfg) {
    this.cfg = cfg;
  }

  public void inject(Object instance) {
    Guice.createInjector(this).injectMembers(instance);
  }

  @Override
  protected void configure() {
    // For simplicity, don't create child injectors, just use this one to get a
    // few required modules.
    Injector cfgInjector = Guice.createInjector(new AbstractModule() {
      @Override
      protected void configure() {
        bind(Config.class).annotatedWith(GerritServerConfig.class)
            .toInstance(cfg);
      }
    });
    install(cfgInjector.getInstance(GerritGlobalModule.class));

    bindScope(RequestScoped.class, PerThreadRequestScope.REQUEST);

    install(new SchemaVersion.Module());

    bind(File.class).annotatedWith(SitePath.class).toInstance(new File("."));
    bind(Config.class).annotatedWith(GerritServerConfig.class).toInstance(cfg);
    bind(SocketAddress.class).annotatedWith(RemotePeer.class).toInstance(
        new InetSocketAddress(InetAddresses.forString("127.0.0.1"), 1234));
    bind(PersonIdent.class)
        .annotatedWith(GerritPersonIdent.class)
        .toProvider(GerritPersonIdentProvider.class);
    bind(String.class)
      .annotatedWith(AnonymousCowardName.class)
      .toProvider(AnonymousCowardNameProvider.class);
    bind(AllProjectsName.class)
        .toProvider(AllProjectsNameProvider.class);
    bind(GitRepositoryManager.class)
        .to(InMemoryRepositoryManager.class);
    bind(InMemoryRepositoryManager.class).in(SINGLETON);
    bind(TrackingFooters.class).toProvider(TrackingFootersProvider.class)
        .in(SINGLETON);

    bind(DataSourceType.class)
      .to(InMemoryH2Type.class);
    bind(new TypeLiteral<SchemaFactory<ReviewDb>>() {})
        .to(InMemoryDatabase.class);

    bind(ChangeHooks.class).to(DisabledChangeHooks.class);
    install(NoSshKeyCache.module());
    install(new CanonicalWebUrlModule() {
      @Override
      protected Class<? extends Provider<String>> provider() {
        return CanonicalWebUrlProvider.class;
      }
    });
    install(new DefaultCacheFactory.Module());
    install(new SmtpEmailSender.Module());
    install(new SignedTokenEmailTokenVerifier.Module());
    install(new MergeabilityChecksExecutorModule());

    IndexType indexType = null;
    try {
      indexType = cfg.getEnum("index", null, "type", IndexType.LUCENE);
    } catch (IllegalArgumentException e) {
      // Custom index type, caller must provide their own module.
    }
    if (indexType != null) {
      switch (indexType) {
        case LUCENE:
          install(luceneIndexModule());
          break;
        default:
          throw new ProvisionException(
              "index type unsupported in tests: " + indexType);
      }
    }
  }

  @Provides
  @Singleton
  @EmailReviewCommentsExecutor
  public WorkQueue.Executor createEmailReviewCommentsExecutor(
      @GerritServerConfig Config config, WorkQueue queues) {
    int poolSize = config.getInt("sendemail", null, "threadPoolSize", 1);
    return queues.createQueue(poolSize, "EmailReviewComments");
  }

  @Provides
  @Singleton
  InMemoryDatabase getInMemoryDatabase(@Current SchemaVersion schemaVersion,
      SchemaCreator schemaCreator) throws OrmException {
    return new InMemoryDatabase(schemaVersion, schemaCreator);
  }

  private Module luceneIndexModule() {
    try {
      int version = cfg.getInt("index", "lucene", "testVersion", -1);
      checkState(ChangeSchemas.ALL.containsKey(version),
          "invalid index.lucene.testVersion %s", version);
      Class<?> clazz =
          Class.forName("com.google.gerrit.lucene.LuceneIndexModule");
      Constructor<?> c =
          clazz.getConstructor(Integer.class, int.class, String.class);
      return (Module) c.newInstance(version, 0, null);
    } catch (ClassNotFoundException | SecurityException | NoSuchMethodException
        | IllegalArgumentException | InstantiationException
        | IllegalAccessException | InvocationTargetException e) {
      ProvisionException pe = new ProvisionException(e.getMessage());
      pe.initCause(e);
      throw pe;
    }
  }
}
