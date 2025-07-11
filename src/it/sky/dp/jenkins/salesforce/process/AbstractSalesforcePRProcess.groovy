#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.process

import it.sky.dp.jenkins.salesforce.common.Configuration
import it.sky.dp.jenkins.salesforce.scm.GithubUtils

import static it.sky.dp.jenkins.salesforce.Constants.*

abstract class AbstractSalesforcePRProcess extends AbstractSalesforceProcess implements Serializable {
    protected GithubUtils githubUtils

    AbstractSalesforcePRProcess(def dsl) {
        super(dsl)
        this.githubUtils = new GithubUtils(dsl)
    }

    @Override
    void initProcessVariables() {
        super.initProcessVariables()

        Configuration cfg = Configuration.getInstance()

        String targetBranch = cfg.getMapValue(MAP_KEY_TARGET_BRANCH_NAME)
        cfg.addEntryToMap(MAP_KEY_BRANCH_NAME, targetBranch, true)
    }

}
