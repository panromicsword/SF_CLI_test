#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.technology

class HtmlReportBuilder implements Serializable {
    protected def dsl
    static final CSS_FILESHEETSTYLE = "body{background-color:#fff;color:#000;margin:0}pre{font-family:monospace;background-color:" +
            "#fff;color:#000;line-height:0;counter-reset:line;margin-top:0;margin-right:0;margin-bottom:" +
            "0;margin-left:0}pre span{display:block;line-height:1.2rem}pre span:before{counter-increment:" +
            "line;content:counter(line);display:inline-block;border-right:1px solid #ddd;padding:0 .5em;" +
            "margin-right:.5em;color:#888}pre spanRed{display:block;line-height:1.2rem;background-color:" +
            "red;color:#000}pre spanRed:before{counter-increment:line;content:counter(line);display:" +
            "inline-block;border-right:1px solid #ddd;padding:0 .5em;margin-right:.5em;color:#888}"

    HtmlReportBuilder(def dsl) {
        this.dsl = dsl
    }

    def generateHtml(String inputLines, def redLines, String filename) {
        def codeLines = inputLines.split("\n")
        String html = getHtml(codeLines, redLines, filename)
        dsl.writeFile file: "${filename}.html", text: html
    }

    private String getHtml(def codeLines, def redLines, String filename) {
        int i = 1

        def htmStrings = []
        htmStrings.add("<html>")
        htmStrings.add("<head>")
        htmStrings.add("    <title>${filename}</title>")
        htmStrings.add("    <style>")
        htmStrings.add("        ${CSS_FILESHEETSTYLE}")
        htmStrings.add("    </style>")
        htmStrings.add("</head>")
        htmStrings.add("<body>")
        htmStrings.add("<pre>")

        codeLines.each {
            if (i in redLines) {
                htmStrings.add("        <spanRed>${it}</spanRed>")
            } else {
                htmStrings.add("        <span>${it}</span>")
            }
            i++
        }

        htmStrings.add("</pre>")
        htmStrings.add("</body>")
        htmStrings.add("</html>")

        return htmStrings.join("\n")
    }
}


    


