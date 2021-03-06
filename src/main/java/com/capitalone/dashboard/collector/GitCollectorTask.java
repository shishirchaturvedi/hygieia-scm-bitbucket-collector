package com.capitalone.dashboard.collector;


import com.capitalone.dashboard.model.Collector;
import com.capitalone.dashboard.model.CollectorItem;
import com.capitalone.dashboard.model.CollectorType;
import com.capitalone.dashboard.model.Commit;
import com.capitalone.dashboard.model.GitRepo;
import com.capitalone.dashboard.repository.BaseCollectorRepository;
import com.capitalone.dashboard.repository.CommitRepository;
import com.capitalone.dashboard.repository.ComponentRepository;
import com.capitalone.dashboard.repository.GitRepoRepository;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CollectorTask that fetches Commit information from Git
 */
@Component
public class GitCollectorTask extends CollectorTask<Collector> {
    private static final Log LOG = LogFactory.getLog(GitCollectorTask.class);

    private final BaseCollectorRepository<Collector> collectorRepository;
    private final GitRepoRepository gitRepoRepository;
    private final CommitRepository commitRepository;
    private final GitClient gitClient;
    private final GitSettings gitSettings;
    private final ComponentRepository dbComponentRepository;

    @Inject
    private PullRequestCollector pullRequestCollector;


    @Autowired
    public GitCollectorTask(TaskScheduler taskScheduler,
                            BaseCollectorRepository<Collector> collectorRepository,
                            GitRepoRepository gitRepoRepository,
                            CommitRepository commitRepository,
                            GitClient gitClient,
                            GitSettings gitSettings,
                            ComponentRepository dbComponentRepository) {
        super(taskScheduler, "Bitbucket");
        this.collectorRepository = collectorRepository;
        this.gitRepoRepository = gitRepoRepository;
        this.commitRepository = commitRepository;
        this.gitClient = gitClient;
        this.gitSettings = gitSettings;
        this.dbComponentRepository = dbComponentRepository;
    }

    @Override
    public Collector getCollector() {
        Collector protoType = new Collector();
        protoType.setName("Bitbucket");
        protoType.setCollectorType(CollectorType.SCM);
        protoType.setOnline(true);
        protoType.setEnabled(true);

        Map<String, Object> allOptions = new HashMap<>();
        allOptions.put(GitRepo.REPO_URL, "");
        allOptions.put(GitRepo.BRANCH, "");
        allOptions.put(GitRepo.USER_ID, "");
        allOptions.put(GitRepo.PASSWORD, "");
        allOptions.put(GitRepo.LAST_UPDATE_TIME, System.currentTimeMillis());
        allOptions.put(GitRepo.LAST_UPDATE_COMMIT, "");
        protoType.setAllFields(allOptions);

        Map<String, Object> uniqueOptions = new HashMap<>();
        uniqueOptions.put(GitRepo.REPO_URL, "");
        uniqueOptions.put(GitRepo.BRANCH, "");
        protoType.setUniqueFields(uniqueOptions);
        return protoType;
    }

    @Override
    public BaseCollectorRepository<Collector> getCollectorRepository() {
        return collectorRepository;
    }

    @Override
    public String getCron() {
        return gitSettings.getCron();
    }

    /**
     * Clean up unused deployment collector items
     *
     * @param collector the {@link Collector}
     */

    private void clean(Collector collector) {
        Set<ObjectId> uniqueIDs = new HashSet<>();
        /**
         * Logic: For each component, retrieve the collector item list of the type SCM.
         * Store their IDs in a unique set ONLY if their collector IDs match with Bitbucket collectors ID.
         */
        for (com.capitalone.dashboard.model.Component comp : dbComponentRepository
                .findAll()) {
            if (comp.getCollectorItems() == null || comp.getCollectorItems()
                                                        .isEmpty()) continue;
            List<CollectorItem> itemList = comp.getCollectorItems()
                                               .get(CollectorType.SCM);
            if (itemList == null) continue;
            for (CollectorItem ci : itemList) {
                if (ci != null && ci.getCollectorId()
                                    .equals(collector.getId())) {
                    uniqueIDs.add(ci.getId());
                }
            }
        }

        /**
         * Logic: Get all the collector items from the collector_item collection for this collector.
         * If their id is in the unique set (above), keep them enabled; else, disable them.
         */
        List<GitRepo> repoList = new ArrayList<GitRepo>();
        Set<ObjectId> gitID = new HashSet<ObjectId>();
        gitID.add(collector.getId());
        for (GitRepo repo : gitRepoRepository.findByCollectorIdIn(gitID)) {
            if (repo != null) {
                repo.setEnabled(uniqueIDs.contains(repo.getId()));
                repoList.add(repo);
            }
        }
        gitRepoRepository.save(repoList);
    }


    @Override
    public void collect(Collector collector) {

        logBanner("Starting...");
        long start = System.currentTimeMillis();
        int repoCount = 0;
        int commitCount = 0;
        int pullCount = 0;

        clean(collector);
        for (int i = 0; i < gitSettings.getHost().size(); i++) {

            String host = gitSettings.getHost().get(i);
            LOG.debug("Settings URL :" + host);
            String userName = gitSettings.getUsername().get(i);
            String password = new String(Base64.decodeBase64(gitSettings.getPassword().get(i)));

            for (GitRepo repo : enabledRepos(collector)) {
                boolean firstRun = false;
                LOG.debug("CollectorItem ID : " +repo.getId());
                if (repo.getLastUpdateTime() == null) firstRun = true;
                String url = repo.getRepoUrl();
                String repoURL = getUrlDomainName(url);
                if (repoURL.equalsIgnoreCase(host)) {
                    LOG.debug("REPO URL : "+repoURL);
                    LOG.debug(repo.getOptions().toString() + "::" + repo.getBranch());
                    List<Commit> commits = gitClient.getCommits(repo, firstRun, userName, password);
                    List<Commit> newCommits = new ArrayList<>();
                    for (Commit commit : commits) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug(commit.getTimestamp() + ":::" + commit
                                    .getScmCommitLog());

                        }
                        if (isNewCommit(repo, commit)) {
                            commit.setCollectorItemId(repo.getId());
                            newCommits.add(commit);
                        }
                    }
                    commitRepository.save(newCommits);
                    commitCount += newCommits.size();


                    if (!commits.isEmpty()) {
                        // It appears that the first commit in the list is the HEAD of the branch
                        repo.setLastUpdateCommit(commits.get(0).getScmRevisionNumber());
                    }

                    // Step 2: Get all the Pull Requests
                    LOG.info(repo.getOptions().toString() + "::" + repo
                            .getBranch() + "::get pulls");

                    pullCount += pullRequestCollector.getPullRequests(repo, "all", userName, password);
                    long time = System.currentTimeMillis();
                    repo.setLastUpdateTime(time);
                    try {
                        gitRepoRepository.save(repo);
                    } catch (ClassCastException e) {
                        LOG.info("Class Cast Exception:", e);
                    }
                    repoCount++;
                }
            }
            log("Repo Count", start, repoCount);
            log("New Commits", start, commitCount);
        }

        log("Finished", start);
    }

    @SuppressWarnings("unused")
    private Long lastUpdated(GitRepo repo) {
        return repo.getLastUpdateTime();
    }
    /*@SuppressWarnings("unused")
    private Date lastUpdated(GitRepo repo) {
        return repo.getLastUpdateTime();
    }*/
    private List<GitRepo> enabledRepos(Collector collector) {
        return gitRepoRepository.findEnabledGitRepos(collector.getId());
    }

    private boolean isNewCommit(GitRepo repo, Commit commit) {
        return commitRepository.findByCollectorItemIdAndScmRevisionNumber(
                repo.getId(), commit.getScmRevisionNumber()) == null;
    }

    private String getUrlDomainName(String url) {
        String domainName = url;
        String domain = null;
        int index = domainName.indexOf("://");
        if (index != -1) {
            domain = domainName.substring(0,index + 3);
            domainName = domainName.substring(index + 3);
        }
        index = domainName.indexOf('/');

        if (index != -1) {
            domainName = domainName.substring(0, index);
        }
        domainName = domain + domainName;
        domainName = domainName.replaceFirst("^www.*?\\.", "");
        return domainName;
    }
}

/*
 * SPDX-Copyright: Copyright (c) Capital One Services, LLC
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2019 Capital One Services, LLC
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
