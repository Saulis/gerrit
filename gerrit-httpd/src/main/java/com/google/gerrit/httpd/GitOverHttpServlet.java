// Copyright (C) 2010 The Android Open Source Project
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

import com.google.common.cache.Cache;
import com.google.common.collect.Lists;
import com.google.gerrit.common.data.Capable;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.AccessPath;
import com.google.gerrit.server.AnonymousUser;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.cache.CacheModule;
import com.google.gerrit.server.git.AsyncReceiveCommits;
import com.google.gerrit.server.git.ChangeCache;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.ReceiveCommits;
import com.google.gerrit.server.git.ReceivePackInitializer;
import com.google.gerrit.server.git.TagCache;
import com.google.gerrit.server.git.TransferConfig;
import com.google.gerrit.server.git.VisibleRefFilter;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.ProjectControl;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Named;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.http.server.GitSmartHttpTools;
import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.http.server.resolver.AsIsFileService;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PostReceiveHook;
import org.eclipse.jgit.transport.PostReceiveHookChain;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.eclipse.jgit.transport.resolver.UploadPackFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Serves Git repositories over HTTP. */
@Singleton
public class GitOverHttpServlet extends GitServlet {
  private static final long serialVersionUID = 1L;

  private static final String ATT_CONTROL = ProjectControl.class.getName();
  private static final String ATT_RC = ReceiveCommits.class.getName();
  private static final String ID_CACHE = "adv_bases";

  public static final String URL_REGEX;
  static {
    StringBuilder url = new StringBuilder();
    url.append("^(?:/p/|/)(.*/(?:info/refs");
    for (String name : GitSmartHttpTools.VALID_SERVICES) {
      url.append('|').append(name);
    }
    url.append("))$");
    URL_REGEX = url.toString();
  }

  static class Module extends AbstractModule {
    @Override
    protected void configure() {
      bind(Resolver.class);
      bind(UploadFactory.class);
      bind(UploadFilter.class);
      bind(ReceiveFactory.class);
      bind(ReceiveFilter.class);
      install(new CacheModule() {
        @Override
        protected void configure() {
          cache(ID_CACHE,
              AdvertisedObjectsCacheKey.class,
              new TypeLiteral<Set<ObjectId>>() {})
            .maximumWeight(4096)
            .expireAfterWrite(10, TimeUnit.MINUTES);
        }
      });
    }
  }

  @Inject
  GitOverHttpServlet(Resolver resolver,
      UploadFactory upload, UploadFilter uploadFilter,
      ReceiveFactory receive, ReceiveFilter receiveFilter) {
    setRepositoryResolver(resolver);
    setAsIsFileService(AsIsFileService.DISABLED);

    setUploadPackFactory(upload);
    addUploadPackFilter(uploadFilter);

    setReceivePackFactory(receive);
    addReceivePackFilter(receiveFilter);
  }

  static class Resolver implements RepositoryResolver<HttpServletRequest> {
    private final GitRepositoryManager manager;
    private final ProjectControl.Factory projectControlFactory;

    @Inject
    Resolver(GitRepositoryManager manager,
        ProjectControl.Factory projectControlFactory) {
      this.manager = manager;
      this.projectControlFactory = projectControlFactory;
    }

    @Override
    public Repository open(HttpServletRequest req, String projectName)
        throws RepositoryNotFoundException, ServiceNotAuthorizedException,
        ServiceNotEnabledException {
      while (projectName.endsWith("/")) {
        projectName = projectName.substring(0, projectName.length() - 1);
      }

      if (projectName.endsWith(".git")) {
        // Be nice and drop the trailing ".git" suffix, which we never keep
        // in our database, but clients might mistakenly provide anyway.
        //
        projectName = projectName.substring(0, projectName.length() - 4);
        while (projectName.endsWith("/")) {
          projectName = projectName.substring(0, projectName.length() - 1);
        }
      }

      final ProjectControl pc;
      try {
        pc = projectControlFactory.controlFor(new Project.NameKey(projectName));
      } catch (NoSuchProjectException err) {
        throw new RepositoryNotFoundException(projectName);
      }

      CurrentUser user = pc.getCurrentUser();
      user.setAccessPath(AccessPath.GIT);

      if (!pc.isVisible()) {
        if (user instanceof AnonymousUser) {
          throw new ServiceNotAuthorizedException();
        } else {
          throw new ServiceNotEnabledException();
        }
      }
      req.setAttribute(ATT_CONTROL, pc);

      try {
        return manager.openRepository(pc.getProject().getNameKey());
      } catch (IOException e) {
        throw new RepositoryNotFoundException(
            pc.getProject().getNameKey().get(), e);
      }
    }
  }

  static class UploadFactory implements UploadPackFactory<HttpServletRequest> {
    private final TransferConfig config;

    @Inject
    UploadFactory(TransferConfig tc) {
      this.config = tc;
    }

    @Override
    public UploadPack create(HttpServletRequest req, Repository repo) {
      UploadPack up = new UploadPack(repo);
      up.setPackConfig(config.getPackConfig());
      up.setTimeout(config.getTimeout());
      return up;
    }
  }

  static class UploadFilter implements Filter {
    private final Provider<ReviewDb> db;
    private final TagCache tagCache;
    private final ChangeCache changeCache;

    @Inject
    UploadFilter(Provider<ReviewDb> db, TagCache tagCache, ChangeCache changeCache) {
      this.db = db;
      this.tagCache = tagCache;
      this.changeCache = changeCache;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
        FilterChain next) throws IOException, ServletException {
      // The Resolver above already checked READ access for us.
      Repository repo = ServletUtils.getRepository(request);
      ProjectControl pc = (ProjectControl) request.getAttribute(ATT_CONTROL);
      UploadPack up = (UploadPack) request.getAttribute(ServletUtils.ATTRIBUTE_HANDLER);

      if (!pc.canRunUploadPack()) {
        GitSmartHttpTools.sendError((HttpServletRequest) request,
            (HttpServletResponse) response, HttpServletResponse.SC_FORBIDDEN,
            "upload-pack not permitted on this server");
        return;
      }

      if (!pc.allRefsAreVisible()) {
        up.setAdvertiseRefsHook(new VisibleRefFilter(tagCache, changeCache, repo, pc, db.get(), true));
      }

      next.doFilter(request, response);
    }

    @Override
    public void init(FilterConfig config) {
    }

    @Override
    public void destroy() {
    }
  }

  static class ReceiveFactory implements ReceivePackFactory<HttpServletRequest> {
    private final AsyncReceiveCommits.Factory factory;
    private final TransferConfig config;
    private DynamicSet<ReceivePackInitializer> receivePackInitializers;
    private DynamicSet<PostReceiveHook> postReceiveHooks;

    @Inject
    ReceiveFactory(AsyncReceiveCommits.Factory factory, TransferConfig config,
        DynamicSet<ReceivePackInitializer> receivePackInitializers,
        DynamicSet<PostReceiveHook> postReceiveHooks) {
      this.factory = factory;
      this.config = config;
      this.receivePackInitializers = receivePackInitializers;
      this.postReceiveHooks = postReceiveHooks;
    }

    @Override
    public ReceivePack create(HttpServletRequest req, Repository db)
        throws ServiceNotAuthorizedException {
      final ProjectControl pc = (ProjectControl) req.getAttribute(ATT_CONTROL);

      if (!(pc.getCurrentUser().isIdentifiedUser())) {
        // Anonymous users are not permitted to push.
        throw new ServiceNotAuthorizedException();
      }

      final IdentifiedUser user = (IdentifiedUser) pc.getCurrentUser();
      final ReceiveCommits rc = factory.create(pc, db).getReceiveCommits();
      ReceivePack rp = rc.getReceivePack();
      rp.setRefLogIdent(user.newRefLogIdent());
      rp.setTimeout(config.getTimeout());
      rp.setMaxObjectSizeLimit(config.getMaxObjectSizeLimit());
      init(pc.getProject().getNameKey(), rp);
      rp.setPostReceiveHook(PostReceiveHookChain.newChain(
          Lists.newArrayList(postReceiveHooks)));
      req.setAttribute(ATT_RC, rc);
      return rp;
    }

    private void init(Project.NameKey project, ReceivePack rp) {
      for (ReceivePackInitializer initializer : receivePackInitializers) {
        initializer.init(project, rp);
      }
    }
  }

  static class ReceiveFilter implements Filter {
    private final Cache<AdvertisedObjectsCacheKey, Set<ObjectId>> cache;

    @Inject
    ReceiveFilter(
        @Named(ID_CACHE) Cache<AdvertisedObjectsCacheKey, Set<ObjectId>> cache) {
      this.cache = cache;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
        FilterChain chain) throws IOException, ServletException {
      boolean isGet =
        "GET".equalsIgnoreCase(((HttpServletRequest) request).getMethod());

      ReceiveCommits rc = (ReceiveCommits) request.getAttribute(ATT_RC);
      ReceivePack rp = rc.getReceivePack();
      rp.getAdvertiseRefsHook().advertiseRefs(rp);
      ProjectControl pc = (ProjectControl) request.getAttribute(ATT_CONTROL);
      Project.NameKey projectName = pc.getProject().getNameKey();

      if (!pc.canRunReceivePack()) {
        GitSmartHttpTools.sendError((HttpServletRequest) request,
            (HttpServletResponse) response, HttpServletResponse.SC_FORBIDDEN,
            "receive-pack not permitted on this server");
        return;
      }

      final Capable s = rc.canUpload();
      if (s != Capable.OK) {
        GitSmartHttpTools.sendError((HttpServletRequest) request,
            (HttpServletResponse) response, HttpServletResponse.SC_FORBIDDEN,
            "\n" + s.getMessage());
        return;
      }

      if (!rp.isCheckReferencedObjectsAreReachable()) {
        chain.doFilter(request, response);
        return;
      }

      if (!(pc.getCurrentUser().isIdentifiedUser())) {
        chain.doFilter(request, response);
        return;
      }

      AdvertisedObjectsCacheKey cacheKey = new AdvertisedObjectsCacheKey(
          ((IdentifiedUser) pc.getCurrentUser()).getAccountId(),
          projectName);

      if (isGet) {
        cache.invalidate(cacheKey);
      } else {
        Set<ObjectId> ids = cache.getIfPresent(cacheKey);
        if (ids != null) {
          rp.getAdvertisedObjects().addAll(ids);
          cache.invalidate(cacheKey);
        }
      }

      chain.doFilter(request, response);

      if (isGet) {
        cache.put(cacheKey, Collections.unmodifiableSet(
            new HashSet<ObjectId>(rp.getAdvertisedObjects())));
      }
    }

    @Override
    public void init(FilterConfig arg0) {
    }

    @Override
    public void destroy() {
    }
  }
}
