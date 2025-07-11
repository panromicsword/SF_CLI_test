package it.sky.dp.jenkins.annotations
import groovy.json.JsonSlurperClassic
import groovy.json.JsonBuilder

class GrafanaAnnotationsMaker implements AnnotationsMaker {

    def steps
    def env

    public GrafanaAnnotationsMaker (steps,env){
        this.steps = steps
        this.env = env
    }

    public String addReleaseAnnotationToBoard(def boardName, def annotationLabel) throws Exception{
        try{

            throw new Exception("NOT IMPLEMENTED!")
        
        }catch(Exception ex){
            println ex
            throw ex
        }
    }

}


