#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.common

class Configuration implements Serializable {
    private static Configuration staticConfig

    private Map map
    private def dsl

    private String lastStage = "INIT"

    private Configuration() {
        this.map = [:]
    }

    static getInstance() {
        if (staticConfig == null) {
            staticConfig = new Configuration()
        }
        return staticConfig
    }

    void setDsl(def dsl) {
        this.dsl = dsl
    }

    def getDsl() {
        return dsl
    }

    void setLastStage(String lastStage) {
        this.dsl.echo "------------------------------ STAGE \"${lastStage}\" ------------------------------"
        this.lastStage = lastStage
    }

    String getLastStage() {
        return lastStage
    }

    void addEntryToMap(String key, def value, boolean checkBeforePut) {
        if (checkBeforePut) {
            this.checkValue(key, value, true)
        }

        if (value instanceof String && value?.trim()) {
            value = value.trim()
        }

        map.put(key, value)
    }

    String getMapValue(String key) {
        return map[key]
    }

    Map getMap() {
        return map
    }

    //@NonCPS
    @Override
    String toString() {
        String str = ""

        this.map.each { key, value ->
            str += key.toString() + ": " + this.map[key].toString() + "\n"
        }

        return str.trim()
    }

    private boolean checkValue(String field, def value, boolean raiseError) {
        boolean isOk = true

        if (value == null) {
            isOk = false
        } else {
            if (value instanceof String && "".equals(value.trim())) {
                isOk = false
            }
        }

        if (raiseError && !isOk) {
            dsl.error(field + "'s value is null or empty")
        }

        return isOk
    }
}
