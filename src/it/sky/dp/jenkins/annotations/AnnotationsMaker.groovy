package it.sky.dp.jenkins.annotations

interface AnnotationsMaker{
    
    public String addReleaseAnnotationToBoard(def boardName, def annotationLabel) throws Exception
}