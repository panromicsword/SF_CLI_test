package it.sky.dp.jenkins.annotations
import groovy.json.JsonSlurperClassic
import groovy.json.JsonBuilder

class AwsAnnotationsMaker implements AnnotationsMaker {

    def steps
    def env

    public AwsAnnotationsMaker (steps,env){
        this.steps = steps
        this.env = env
    }

    public String addReleaseAnnotationToBoard(def boardName, def annotationLabel) throws Exception{
        try{

            def boardsList;
            def envBoardList;
            
            def releaseAnnotation = new JsonSlurperClassic().parseText('''{"label":"''' +annotationLabel + '''", "value": "''' + new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC")) + '''"}''')

            def boardJsonString = steps.sh(returnStdout: true, script: "aws cloudwatch get-dashboard --dashboard-name ${boardName}")

            def boardJson = new JsonSlurperClassic().parseText( boardJsonString )

            def boardBodyJson = new JsonSlurperClassic().parseText( boardJson.DashboardBody )

            def widgets = boardBodyJson.widgets
                                    
            widgets.eachWithIndex{ widget, wIndex ->
                
                //can't annotate  alarms
                if(widget.type.equalsIgnoreCase("metric") 
                    && !widget.properties?.annotations?.keySet()?.contains("alarms")){
                    
                    println "widget ${wIndex}"

                    println "current annotations: " + new JsonBuilder(widget.properties.annotations).toPrettyString()

                    if(!widget.properties.keySet().contains("annotations")){
                        widget.properties.annotations = new JsonSlurperClassic().parseText('''{"vertical":[]}''')
                    }else if(!widget.properties.annotations.keySet().contains("vertical")){
                        widget.properties.annotations.vertical = new ArrayList()
                    }

                    widget.properties.annotations.vertical.add(releaseAnnotation)
                    
                    println "final annotations: " + new JsonBuilder(widget.properties.annotations).toPrettyString()    
                }
            }
            
            def boardBodyJsonString = new JsonBuilder(boardBodyJson).toPrettyString()

            def putDashboardResult = steps.sh(returnStdout: true, script: "aws cloudwatch put-dashboard --dashboard-name ${boardName} --dashboard-body '${boardBodyJsonString}'")

            return putDashboardResult
        
        }catch(Exception ex){
            println ex
            throw ex
        }
    }

}


