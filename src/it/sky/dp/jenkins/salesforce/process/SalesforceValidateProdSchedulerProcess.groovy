#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.process

import static it.sky.dp.jenkins.salesforce.Constants.*

class SalesforceValidateProdSchedulerProcess extends AbstractProcess implements Serializable {

    SalesforceValidateProdSchedulerProcess(def dsl) {
        super(dsl)
    }

    @Override
    void checkParameters() {
        super.checkParam(MAP_KEY_SOURCE_BRANCH_NAME)
        super.checkParam(MAP_KEY_TARGET_BRANCH_NAME)
        super.checkParam(MAP_KEY_TARGET_ENVIRONMENT)
        super.checkParam(MAP_KEY_TEST_LEVEL)
        super.checkParam(MAP_KEY_SKIP_STORE)
        super.checkParam(MAP_KEY_RELEASE_VERSION)
        super.checkParam(MAP_KEY_SKIP_SCA)
    }

}
