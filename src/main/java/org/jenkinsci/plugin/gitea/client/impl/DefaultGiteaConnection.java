/*
 * The MIT License
 *
 * Copyright (c) 2017-2020, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugin.gitea.client.impl;

import com.damnhandy.uri.template.UriTemplate;
import com.damnhandy.uri.template.UriTemplateBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import javax.net.ssl.HttpsURLConnection;
import jenkins.model.Jenkins;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugin.gitea.client.api.GiteaAnnotatedTag;
import org.jenkinsci.plugin.gitea.client.api.GiteaAuth;
import org.jenkinsci.plugin.gitea.client.api.GiteaAuthToken;
import org.jenkinsci.plugin.gitea.client.api.GiteaAuthUser;
import org.jenkinsci.plugin.gitea.client.api.GiteaBranch;
import org.jenkinsci.plugin.gitea.client.api.GiteaCommitDetail;
import org.jenkinsci.plugin.gitea.client.api.GiteaCommitStatus;
import org.jenkinsci.plugin.gitea.client.api.GiteaConnection;
import org.jenkinsci.plugin.gitea.client.api.GiteaHook;
import org.jenkinsci.plugin.gitea.client.api.GiteaHttpStatusException;
import org.jenkinsci.plugin.gitea.client.api.GiteaIssue;
import org.jenkinsci.plugin.gitea.client.api.GiteaIssueState;
import org.jenkinsci.plugin.gitea.client.api.GiteaOrganization;
import org.jenkinsci.plugin.gitea.client.api.GiteaOwner;
import org.jenkinsci.plugin.gitea.client.api.GiteaPullRequest;
import org.jenkinsci.plugin.gitea.client.api.GiteaRepository;
import org.jenkinsci.plugin.gitea.client.api.GiteaTag;
import org.jenkinsci.plugin.gitea.client.api.GiteaUser;
import org.jenkinsci.plugin.gitea.client.api.GiteaVersion;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Default implementation of {@link GiteaConnection} that uses the JVM native {@link URLConnection} to communicate
 * with a remote Gitea server. Requires a valid end-point. Package protected to ensure access goes through the
 * API and not direct construction.
 */
class DefaultGiteaConnection implements GiteaConnection {

    private final String serverUrl;

    private final GiteaAuth authentication;
    private final ObjectMapper mapper = new ObjectMapper();

    DefaultGiteaConnection(@NonNull String serverUrl,
                           @NonNull GiteaAuth authentication) {
        this.serverUrl = serverUrl;
        this.authentication = authentication;
    }

    /**
     * Workaround for a bug in {@code HttpURLConnection.setRequestMethod(String)}
     * The implementation of Sun/Oracle is throwing a {@code ProtocolException}
     * when the method is other than the HTTP/1.1 default methods. So to use {@code PROPFIND}
     * and others, we must apply this workaround.
     */
    private static void setRequestMethodViaJreBugWorkaround(final HttpURLConnection httpURLConnection,
                                                            final String method) {
        try {
            httpURLConnection.setRequestMethod(method); // Check whether we are running on a buggy JRE
        } catch (final ProtocolException pe) {
            try {
                AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
                    @Override
                    public Object run() throws NoSuchFieldException, IllegalAccessException {
                        final Object target;
                        if (httpURLConnection instanceof HttpsURLConnection) {
                            final Field delegate = httpURLConnection.getClass().getDeclaredField("delegate");
                            delegate.setAccessible(true);
                            target = delegate.get(httpURLConnection);
                        } else {
                            target = httpURLConnection;
                        }
                        final Field methodField = HttpURLConnection.class.getDeclaredField("method");
                        methodField.setAccessible(true);
                        methodField.set(target, method);
                        return null;
                    }
                });
            } catch (final PrivilegedActionException e) {
                final Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                } else {
                    throw new RuntimeException(cause);
                }
            }
        }
    }

    @Override
    public GiteaVersion fetchVersion() throws IOException, InterruptedException {
        return getObject(
                api()
                        .literal("/version")
                        .build(),
                GiteaVersion.class
        );
    }

    @Override
    public GiteaUser fetchCurrentUser() throws IOException, InterruptedException {
        return getObject(
                api()
                        .literal("/user")
                        .build(),
                GiteaUser.class
        );
    }

    @Override
    public GiteaOwner fetchOwner(String name) throws IOException, InterruptedException {
        try {
            GiteaOrganization giteaOrganization = fetchOrganization(name);
            if (giteaOrganization != null) {
                return giteaOrganization;
            }
        } catch (GiteaHttpStatusException e) {
            // When it's NotFound, owner might be a user, so only rethrow when not 404
            // Every other non 200 status code should be thrown again by fetchUser()
            if (e.getStatusCode() != 404) {
                throw e;
            }
        }
        return fetchUser(name);
    }

    @Override
    public GiteaUser fetchUser(String name) throws IOException, InterruptedException {
        return getObject(
                api()
                        .literal("/users")
                        .path(UriTemplateBuilder.var("name"))
                        .build()
                        .set("name", name),
                GiteaUser.class
        );
    }

    @Override
    public GiteaOrganization fetchOrganization(String name) throws IOException, InterruptedException {
        return getObject(
                api()
                        .literal("/orgs")
                        .path(UriTemplateBuilder.var("name"))
                        .build()
                        .set("name", name),
                GiteaOrganization.class
        );
    }

    @Override
    public GiteaRepository fetchRepository(String username, String name) throws IOException, InterruptedException {
        return getObject(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .build()
                        .set("username", username)
                        .set("name", name),
                GiteaRepository.class
        );
    }

    @Override
    public GiteaRepository fetchRepository(GiteaOwner owner, String name) throws IOException, InterruptedException {
        return fetchRepository(owner.getUsername(), name);
    }

    @Override
    public List<GiteaRepository> fetchCurrentUserRepositories() throws IOException, InterruptedException {
        List<GiteaRepository> repos = new ArrayList<GiteaRepository>();
        for (int page = 2; true; page++){
            List<GiteaRepository> temp = getList(
                    api()
                            .literal("/users")
                            .literal("/repos")
                            .query(UriTemplateBuilder.var("page"))
                            .build()
                            .set("page", page),
                    GiteaRepository.class
            );
            if(temp.size()>0){
                repos.addAll(temp);
            }else{
                return repos;
            }
        }
    }

    @Override
    public List<GiteaRepository> fetchRepositories(String username) throws IOException, InterruptedException {
        List<GiteaRepository> repos = new ArrayList<GiteaRepository>();
        for (int page = 1; true; page++){
            List<GiteaRepository> temp = getList(
                    api()
                            .literal("/users")
                            .path(UriTemplateBuilder.var("username"))
                            .literal("/repos")
                            .query(UriTemplateBuilder.var("page"))
                            .build()
                            .set("username", username)
                            .set("page", page),
                    GiteaRepository.class
            );
            if(temp.size()>0){
                repos.addAll(temp);
            }else{
                return repos;
            }
        }
    }

    @Override
    public List<GiteaRepository> fetchRepositories(GiteaOwner owner) throws IOException, InterruptedException {
        if(owner instanceof GiteaOrganization) {
            return fetchOrganizationRepositories(owner);
        }
        return fetchRepositories(owner.getUsername());

    }

    @Override
    public List<GiteaRepository> fetchOrganizationRepositories(GiteaOwner owner) throws IOException, InterruptedException {
        List<GiteaRepository> repos = new ArrayList<GiteaRepository>();
        for (int page = 1;true; page++){
            List<GiteaRepository> temp = getList(
                    api()
                            .literal("/orgs")
                            .path(UriTemplateBuilder.var("org"))
                            .literal("/repos")
                            .query(UriTemplateBuilder.var("page"))
                            .build()
                            .set("org", owner.getUsername())
                            .set("page", page),
                    GiteaRepository.class
            );
            if(temp.size()>0){
                repos.addAll(temp);
            }else{
                return repos;
            }
        }
    }

    @Override
    public GiteaBranch fetchBranch(String username, String repository, String name)
            throws IOException, InterruptedException {
        if (name.indexOf('/') != -1) {
            // TODO remove hack once https://github.com/go-gitea/gitea/issues/2088 is fixed
            for (GiteaBranch b : fetchBranches(username, repository)) {
                if (name.equals(b.getName())) {
                    return b;
                }
            }
        }
        return getObject(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("repository"))
                        .literal("/branches")
                        .path(UriTemplateBuilder.var("name", true))
                        .build()
                        .set("username", username)
                        .set("repository", repository)
                        .set("name", StringUtils.split(name, '/')),
                GiteaBranch.class
        );
    }

    @Override
    public GiteaBranch fetchBranch(GiteaRepository repository, String name) throws IOException, InterruptedException {
        return fetchBranch(repository.getOwner().getUsername(), repository.getName(), name);
    }

    @Override
    public List<GiteaBranch> fetchBranches(String username, String name) throws IOException, InterruptedException {
        List<GiteaBranch> branches = new ArrayList<GiteaBranch>();
        for (int page = 1; true; page++){
            List<GiteaBranch> temp = getList(
                    api()
                            .literal("/repos")
                            .path(UriTemplateBuilder.var("username"))
                            .path(UriTemplateBuilder.var("name"))
                            .literal("/branches")
                            .query(UriTemplateBuilder.var("page"))
                            .build()
                            .set("username", username)
                            .set("name", name)
                            .set("page", page),
                        GiteaBranch.class
            );
            if(temp.size()>0){
                branches.addAll(temp);
            }else{
                return branches;
            }
        }
    }

    @Override
    public List<GiteaBranch> fetchBranches(GiteaRepository repository) throws IOException, InterruptedException {
        return fetchBranches(repository.getOwner().getUsername(), repository.getName());
    }

    @Override
    public GiteaAnnotatedTag fetchAnnotatedTag(String username, String repository, String sha1)
            throws IOException, InterruptedException {
        return getObject(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("repository"))
                        .literal("/git/tags")
                        .path(UriTemplateBuilder.var("sha1"))
                        .build()
                        .set("username", username)
                        .set("repository", repository)
                        .set("sha1", sha1),
                GiteaAnnotatedTag.class
        );
    }

    @Override
    public GiteaAnnotatedTag fetchAnnotatedTag(GiteaRepository repository, GiteaTag tag) throws IOException, InterruptedException {
        return fetchAnnotatedTag(repository.getOwner().getUsername(), repository.getName(), tag.getId());
    }

    @Override
    public List<GiteaTag> fetchTags(String username, String name) throws IOException, InterruptedException {
        List<GiteaTag> tags = new ArrayList<GiteaTag>();
        for (int page = 1; true; page++){
            List<GiteaTag> temp = getList(
                    api()
                            .literal("/repos")
                            .path(UriTemplateBuilder.var("username"))
                            .path(UriTemplateBuilder.var("name"))
                            .literal("/tags")
                            .query(UriTemplateBuilder.var("page"))
                            .build()
                            .set("username", username)
                            .set("name", name)
                            .set("page", page),
                    GiteaTag.class
            );
            if(temp.size()>0){
                tags.addAll(temp);
            }else{
                return tags;
            }
        }
    }

    @Override
    public List<GiteaTag> fetchTags(GiteaRepository repository) throws IOException, InterruptedException {
        return fetchTags(repository.getOwner().getUsername(), repository.getName());
    }

    @Override
    public GiteaCommitDetail fetchCommit(String username, String repository, String sha1)
            throws IOException, InterruptedException {
        return getObject(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("repository"))
                        .literal("/git/commits")
                        .path(UriTemplateBuilder.var("sha1"))
                        .build()
                        .set("username", username)
                        .set("repository", repository)
                        .set("sha1", sha1),
                GiteaCommitDetail.class
        );
    }

    @Override
    public GiteaCommitDetail fetchCommit(GiteaRepository repository, String sha1)
            throws IOException, InterruptedException {
        return fetchCommit(repository.getOwner().getUsername(), repository.getName(), sha1);
    }

    @Override
    public List<GiteaUser> fetchCollaborators(String username, String name) throws IOException, InterruptedException {
        List<GiteaUser> users = new ArrayList<GiteaUser>();
        for (int page = 1; true; page++){
            List<GiteaUser> temp = getList(
                    api()
                            .literal("/repos")
                            .path(UriTemplateBuilder.var("username"))
                            .path(UriTemplateBuilder.var("name"))
                            .literal("/collaborators")
                            .query(UriTemplateBuilder.var("page"))
                            .build()
                            .set("username", username)
                            .set("name", name)
                            .set("page", page),
                            GiteaUser.class
            );
            if(temp.size()>0){
                users.addAll(temp);
            }else{
                return users;
            }
        }
    }

    @Override
    public List<GiteaUser> fetchCollaborators(GiteaRepository repository) throws IOException, InterruptedException {
        return fetchCollaborators(repository.getOwner().getUsername(), repository.getName());
    }

    @Override
    public boolean checkCollaborator(String username, String name, String collaboratorName)
            throws IOException, InterruptedException {
        return status(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/collaborators")
                        .path(UriTemplateBuilder.var("collaboratorName"))
                        .build()
                        .set("username", username)
                        .set("name", name)
                        .set("collaboratorName", collaboratorName)
        ) / 100 == 2;
    }

    @Override
    public boolean checkCollaborator(GiteaRepository repository, String collaboratorName)
            throws IOException, InterruptedException {
        return checkCollaborator(repository.getOwner().getUsername(), repository.getName(), collaboratorName);
    }

    @Override
    public List<GiteaHook> fetchHooks(String organizationName) throws IOException, InterruptedException {
        List<GiteaHook> hooks = new ArrayList<GiteaHook>();
        for (int page = 1; true; page++){
            List<GiteaHook> temp = getList(
                    api()
                            .literal("/orgs")
                            .path(UriTemplateBuilder.var("name"))
                            .literal("/hooks")
                            .query(UriTemplateBuilder.var("page"))
                            .build()
                            .set("name", organizationName)
                            .set("page", page),
                            GiteaHook.class
            );
            if(temp.size()>0){
                hooks.addAll(temp);
            }else{
                return hooks;
            }
        }
    }

    @Override
    public List<GiteaHook> fetchHooks(GiteaOrganization organization) throws IOException, InterruptedException {
        return fetchHooks(organization.getUsername());
    }

    @Override
    public GiteaHook createHook(GiteaOrganization organization, GiteaHook hook)
            throws IOException, InterruptedException {
        return post(api()
                        .literal("/orgs")
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/hooks")
                        .build()
                        .set("name", organization.getUsername()),
                hook, GiteaHook.class);
    }

    @Override
    public void deleteHook(GiteaOrganization organization, GiteaHook hook) throws IOException, InterruptedException {
        deleteHook(organization, hook.getId());
    }

    @Override
    public void deleteHook(GiteaOrganization organization, long id) throws IOException, InterruptedException {
        int status = delete(api()
                .literal("/orgs")
                .path(UriTemplateBuilder.var("name"))
                .literal("/hooks")
                .path(UriTemplateBuilder.var("id"))
                .build()
                .set("name", organization.getUsername())
                .set("id", id)
        );
        if (status / 100 != 2) {
            throw new IOException(
                    "Could not delete organization hook " + id + " for " + organization.getUsername() + " HTTP/"
                            + status);
        }
    }

    @Override
    public List<GiteaHook> fetchHooks(String username, String name) throws IOException, InterruptedException {
        List<GiteaHook> hooks = new ArrayList<GiteaHook>();
        for (int page = 1; true; page++){
            List<GiteaHook> temp = getList(
                    api()
                            .literal("/repos")
                            .path(UriTemplateBuilder.var("username"))
                            .path(UriTemplateBuilder.var("name"))
                            .literal("/hooks")
                            .query(UriTemplateBuilder.var("page"))
                            .build()
                            .set("username", username)
                            .set("name", name)
                            .set("page", page),
                            GiteaHook.class
            );
            if(temp.size()>0){
                hooks.addAll(temp);
            }else{
                return hooks;
            }
        }
    }

    @Override
    public List<GiteaHook> fetchHooks(GiteaRepository repository) throws IOException, InterruptedException {
        return fetchHooks(repository.getOwner().getUsername(), repository.getName());
    }

    @Override
    public GiteaHook createHook(GiteaRepository repository, GiteaHook hook) throws IOException, InterruptedException {
        return post(api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/hooks")
                        .build()
                        .set("username", repository.getOwner().getUsername())
                        .set("name", repository.getName()),
                hook, GiteaHook.class);
    }

    @Override
    public void deleteHook(GiteaRepository repository, GiteaHook hook) throws IOException, InterruptedException {
        deleteHook(repository, hook.getId());
    }

    @Override
    public void deleteHook(GiteaRepository repository, long id) throws IOException, InterruptedException {
        int status = delete(api()
                .literal("/repos")
                .path(UriTemplateBuilder.var("username"))
                .path(UriTemplateBuilder.var("name"))
                .literal("/hooks")
                .path(UriTemplateBuilder.var("id"))
                .build()
                .set("username", repository.getOwner().getUsername())
                .set("name", repository.getName())
                .set("id", id)
        );
        if (status / 100 != 2) {
            throw new IOException(
                    "Could not delete hook " + id + " for " + repository.getOwner().getUsername() + "/" + repository
                            .getName() + " HTTP/" + status);
        }
    }

    @Override
    public void updateHook(GiteaOrganization organization, GiteaHook hook) throws IOException, InterruptedException {
        GiteaHook diff = new GiteaHook();
        diff.setConfig(hook.getConfig());
        diff.setActive(hook.isActive());
        diff.setEvents(hook.getEvents());
        patch(api()
                        .literal("/orgs")
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/hooks")
                        .path(UriTemplateBuilder.var("id"))
                        .build()
                        .set("name", organization.getUsername())
                        .set("id", hook.getId()),
                diff, Void.class);
    }

    @Override
    public void updateHook(GiteaRepository repository, GiteaHook hook) throws IOException, InterruptedException {
        GiteaHook diff = new GiteaHook();
        diff.setConfig(hook.getConfig());
        diff.setActive(hook.isActive());
        diff.setEvents(hook.getEvents());
        patch(api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/hooks")
                        .path(UriTemplateBuilder.var("id"))
                        .build()
                        .set("username", repository.getOwner().getUsername())
                        .set("name", repository.getName())
                        .set("id", hook.getId()),
                diff, Void.class);
    }

    @Override
    public List<GiteaCommitStatus> fetchCommitStatuses(GiteaRepository repository, String sha)
            throws IOException, InterruptedException {
        List<GiteaCommitStatus> status = new ArrayList<GiteaCommitStatus>();
        for (int page = 1; true; page++){
            List<GiteaCommitStatus> temp = getList(
                    api()
                            .literal("/repos")
                            .path(UriTemplateBuilder.var("username"))
                            .path(UriTemplateBuilder.var("name"))
                            .literal("/statuses")
                            .path(UriTemplateBuilder.var("sha"))
                            .query(UriTemplateBuilder.var("page"))
                            .build()
                            .set("username", repository.getOwner().getUsername())
                            .set("name", repository.getName())
                            .set("sha", sha)
                            .set("page", page),
                            GiteaCommitStatus.class
            );
            if(temp.size()>0){
                status.addAll(temp);
            }else{
                return status;
            }
        }
    }

    @Override
    public GiteaCommitStatus createCommitStatus(String username, String repository, String sha,
                                                GiteaCommitStatus status) throws IOException, InterruptedException {
        return post(api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/statuses")
                        .path(UriTemplateBuilder.var("sha"))
                        .build()
                        .set("username", username)
                        .set("name", repository)
                        .set("sha", sha),
                status, GiteaCommitStatus.class);
    }

    @Override
    public GiteaCommitStatus createCommitStatus(GiteaRepository repository, String sha, GiteaCommitStatus status)
            throws IOException, InterruptedException {
        return createCommitStatus(repository.getOwner().getUsername(), repository.getName(), sha, status);
    }

    @Override
    public GiteaPullRequest fetchPullRequest(String username, String name, long id)
            throws IOException, InterruptedException {
        return getObject(
                api()
                        .literal("/repos")
                        .path(UriTemplateBuilder.var("username"))
                        .path(UriTemplateBuilder.var("name"))
                        .literal("/pulls")
                        .path(UriTemplateBuilder.var("id"))
                        .build()
                        .set("username", username)
                        .set("name", name)
                        .set("id", Long.toString(id)),
                GiteaPullRequest.class
        );
    }

    @Override
    public GiteaPullRequest fetchPullRequest(GiteaRepository repository, long id)
            throws IOException, InterruptedException {
        return fetchPullRequest(repository.getOwner().getUsername(), repository.getName(), id);
    }

    @Override
    public List<GiteaPullRequest> fetchPullRequests(String username, String name)
            throws IOException, InterruptedException {
        return fetchPullRequests(username, name, EnumSet.of(GiteaIssueState.OPEN));
    }

    @Override
    public List<GiteaPullRequest> fetchPullRequests(GiteaRepository repository)
            throws IOException, InterruptedException {
        return fetchPullRequests(repository, EnumSet.of(GiteaIssueState.OPEN));
    }

    @Override
    public List<GiteaPullRequest> fetchPullRequests(String username, String name, Set<GiteaIssueState> states)
            throws IOException, InterruptedException {
        String state = null;
        if (states != null && states.size() == 1) {
            // state query only works if there is one state
            for (GiteaIssueState s : GiteaIssueState.values()) {
                if (states.contains(s)) {
                    state = s.getKey();
                }
            }
        }
        try {
            List<GiteaPullRequest> requests = new ArrayList<GiteaPullRequest>();
            for (int page = 1; true; page++){
                List<GiteaPullRequest> temp = getList(
                        api()
                                .literal("/repos")
                                .path(UriTemplateBuilder.var("username"))
                                .path(UriTemplateBuilder.var("name"))
                                .literal("/pulls")
                                .query(UriTemplateBuilder.var("state"), UriTemplateBuilder.var("page"))
                                .build()
                                .set("username", username)
                                .set("name", name)
                                .set("state", state)
                                .set("page", page),
                                GiteaPullRequest.class
                );
                if(temp.size()>0){
                    requests.addAll(temp);
                }else{
                    return requests;
                }
            }
        } catch (GiteaHttpStatusException e) {
            // Gitea REST API returns HTTP Code 404 when pull requests or issues are disabled
            // Therefore we need to handle this case and return a empty List
            if (e.getStatusCode() == 404) {
                return Collections.emptyList();
            } else {
                // Else other cause... throw exception again
                throw e;
            }
        }
    }

    @Override
    public List<GiteaPullRequest> fetchPullRequests(GiteaRepository repository, Set<GiteaIssueState> states)
            throws IOException, InterruptedException {
        return fetchPullRequests(repository.getOwner().getUsername(), repository.getName(), states);
    }

    @Override
    public List<GiteaIssue> fetchIssues(String username, String name)
            throws IOException, InterruptedException {
        return fetchIssues(username, name, EnumSet.of(GiteaIssueState.OPEN));
    }

    @Override
    public List<GiteaIssue> fetchIssues(GiteaRepository repository)
            throws IOException, InterruptedException {
        return fetchIssues(repository, EnumSet.of(GiteaIssueState.OPEN));
    }

    @Override
    public List<GiteaIssue> fetchIssues(String username, String name, Set<GiteaIssueState> states)
            throws IOException, InterruptedException {
        String state = null;
        if (states != null && states.size() == 1) {
            // state query only works if there is one state
            for (GiteaIssueState s : GiteaIssueState.values()) {
                if (states.contains(s)) {
                    state = s.getKey();
                }
            }
        }

        try {
            List<GiteaIssue> issues = new ArrayList<GiteaIssue>();
            for (int page = 1; true; page++){
                List<GiteaIssue> temp = getList(
                        api()
                                .literal("/repos")
                                .path(UriTemplateBuilder.var("username"))
                                .path(UriTemplateBuilder.var("name"))
                                .literal("/issues")
                                .query(UriTemplateBuilder.var("state"), UriTemplateBuilder.var("page"))
                                .build()
                                .set("username", username)
                                .set("name", name)
                                .set("state", state)
                                .set("page", page),
                                GiteaIssue.class
                );
                if(temp.size()>0){
                    issues.addAll(temp);
                }else{
                    return issues;
                }
            }

        } catch (GiteaHttpStatusException e) {
            // Gitea REST API returns HTTP Code 404 when pull requests or issues are disabled
            // Therefore we need to handle this case and return a empty List
            if (e.getStatusCode() == 404) {
                return Collections.emptyList();
            } else {
                // Else other cause... throw exception again
                throw e;
            }
        }
    }

    @Override
    public List<GiteaIssue> fetchIssues(GiteaRepository repository, Set<GiteaIssueState> states)
            throws IOException, InterruptedException {
        return fetchIssues(repository.getOwner().getUsername(), repository.getName(), states);
    }

    @Override
    public byte[] fetchFile(GiteaRepository repository, String ref, String path)
            throws IOException, InterruptedException {
        HttpURLConnection connection = openConnection(api()
                .literal("/repos")
                .path(UriTemplateBuilder.var("username"))
                .path(UriTemplateBuilder.var("name"))
                .literal("/raw")
                .path(UriTemplateBuilder.var("ref", true))
                .path(UriTemplateBuilder.var("path", true))
                .build()
                .set("username", repository.getOwner().getUsername())
                .set("name", repository.getName())
                .set("ref", StringUtils.split(ref, '/'))
                .set("path", StringUtils.split(path, "/")));
        withAuthentication(connection);
        try {
            connection.connect();
            int status = connection.getResponseCode();
            if (status == 404) {
                throw new FileNotFoundException(path);
            }
            if (status / 100 == 2) {
                try (InputStream is = connection.getInputStream()) {
                    return IOUtils.toByteArray(is);
                }
            }
            throw new IOException("HTTP " + status + "/" + connection.getResponseMessage());
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public boolean checkFile(GiteaRepository repository, String ref, String path)
            throws IOException, InterruptedException {
        HttpURLConnection connection = openConnection(api()
                .literal("/repos")
                .path(UriTemplateBuilder.var("username"))
                .path(UriTemplateBuilder.var("name"))
                .literal("/raw")
                .path(UriTemplateBuilder.var("ref", true))
                .path(UriTemplateBuilder.var("path", true))
                .build()
                .set("username", repository.getOwner().getUsername())
                .set("name", repository.getName())
                .set("ref", StringUtils.split(ref, '/'))
                .set("path", StringUtils.split(path, "/")));
        withAuthentication(connection);
        try {
            connection.connect();
            int status = connection.getResponseCode();
            if (status == 404) {
                return false;
            }
            if (status / 100 == 2) {
                return true;
            }
            throw new IOException("HTTP " + status + "/" + connection.getResponseMessage());
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public void close() throws IOException {
    }

    private UriTemplateBuilder api() {
        return UriTemplate.buildFromTemplate(serverUrl).literal("/api/v1");
    }

    private void withAuthentication(HttpURLConnection connection) {
        if (authentication instanceof GiteaAuthUser) {
            String auth = (((GiteaAuthUser) authentication).getUsername()) + ":" + (((GiteaAuthUser) authentication).
                    getPassword());
            connection.setRequestProperty("Authorization", "Basic " + Base64.encodeBase64String(auth.getBytes(
                    StandardCharsets.UTF_8)));
        } else if (authentication instanceof GiteaAuthToken) {
            connection.setRequestProperty("Authorization", "token " + ((GiteaAuthToken) authentication).getToken());
        }
    }

    private int status(UriTemplate template) throws IOException, InterruptedException {
        HttpURLConnection connection = openConnection(template);
        withAuthentication(connection);
        try {
            connection.connect();
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private int delete(UriTemplate template) throws IOException, InterruptedException {
        HttpURLConnection connection = openConnection(template);
        withAuthentication(connection);
        connection.setRequestMethod("DELETE");
        try {
            connection.connect();
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private <T> T getObject(UriTemplate template, final Class<T> modelClass) throws IOException, InterruptedException {
        HttpURLConnection connection = openConnection(template);
        withAuthentication(connection);
        try {
            connection.connect();
            int status = connection.getResponseCode();
            if (status == 200) {
                try (InputStream is = connection.getInputStream()) {
                    return mapper.readerFor(modelClass).readValue(is);
                }
            }
            throw new GiteaHttpStatusException(status, connection.getResponseMessage());
        } finally {
            connection.disconnect();
        }
    }

    private <T> T post(UriTemplate template, Object body, final Class<T> modelClass)
            throws IOException, InterruptedException {
        HttpURLConnection connection = openConnection(template);
        withAuthentication(connection);
        connection.setRequestMethod("POST");
        byte[] bytes;
        if (body != null) {
            bytes = mapper.writer(new StdDateFormat()).writeValueAsBytes(body);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Content-Length", Integer.toString(bytes.length));
            connection.setDoOutput(true);
        } else {
            bytes = null;
            connection.setDoOutput(false);
        }
        connection.setDoInput(!Void.class.equals(modelClass));

        try {
            connection.connect();
            if (bytes != null) {
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            if (status / 100 == 2) {
                if (Void.class.equals(modelClass)) {
                    return null;
                }
                try (InputStream is = connection.getInputStream()) {
                    return mapper.readerFor(modelClass).readValue(is);
                }
            }
            throw new GiteaHttpStatusException(
                    status,
                    connection.getResponseMessage(),
                    bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null
            );
        } finally {
            connection.disconnect();
        }
    }

    private <T> T patch(UriTemplate template, Object body, final Class<T> modelClass)
            throws IOException, InterruptedException {
        HttpURLConnection connection = openConnection(template);
        withAuthentication(connection);
        setRequestMethodViaJreBugWorkaround(connection, "PATCH");
        byte[] bytes;
        if (body != null) {
            bytes = mapper.writer(new StdDateFormat()).writeValueAsBytes(body);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Content-Length", Integer.toString(bytes.length));
            connection.setDoOutput(true);
        } else {
            bytes = null;
            connection.setDoOutput(false);
        }
        connection.setDoInput(true);

        try {
            connection.connect();
            if (bytes != null) {
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            if (status / 100 == 2) {
                if (Void.class.equals(modelClass)) {
                    return null;
                }
                try (InputStream is = connection.getInputStream()) {
                    return mapper.readerFor(modelClass).readValue(is);
                }
            }
            throw new GiteaHttpStatusException(
                    status,
                    connection.getResponseMessage(),
                    bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null
            );
        } finally {
            connection.disconnect();
        }
    }

    private <T> List<T> getList(UriTemplate template, final Class<T> modelClass)
            throws IOException, InterruptedException {
        HttpURLConnection connection = openConnection(template);
        withAuthentication(connection);
        try {
            connection.connect();
            int status = connection.getResponseCode();
            if (status / 100 == 2) {
                try (InputStream is = connection.getInputStream()) {
                    List<T> list = mapper.readerFor(mapper.getTypeFactory()
                            .constructCollectionType(List.class, modelClass))
                            .readValue(is);
                    // strip null values from the list
                    for (Iterator<T> iterator = list.iterator(); iterator.hasNext(); ) {
                        if (iterator.next() == null) {
                            iterator.remove();
                        }
                    }
                    return list;
                }
            }
            throw new GiteaHttpStatusException(status, connection.getResponseMessage());
        } finally {
            connection.disconnect();
        }
    }

    @Restricted(NoExternalUse.class)
    protected HttpURLConnection openConnection(UriTemplate template) throws IOException {
        URL url = new URL(template.expand());
        Jenkins jenkins = Jenkins.get();
        if (jenkins.proxy == null) {
            return (HttpURLConnection) url.openConnection();
        }
        return (HttpURLConnection) url.openConnection(jenkins.proxy.createProxy(url.getHost()));
    }

}
