package it.sky.dp.jenkins.salesforce.technology

import com.cloudbees.groovy.cps.NonCPS
import org.apache.commons.csv.CSVFormat

import static it.sky.dp.jenkins.salesforce.Constants.*

class CsvUtils implements Serializable {
    final def COLUMN_ID = "ID"
    final def COLUMN_PHASE = "PRE/POST"
    final def COLUMN_ENVIRONMENT = "ENVIRONMENT"
    final def COLUMN_FILENAME = "FILENAME"
    final def COLUMN_HAS_ATTACHMENTS = "HAS_ATTACHMENTS"
    final def COLUMN_REQUESTER = "REQUESTER"
    final def COLUMN_DURATION = "DURATION"

    final def COLUMN_CM_PROD = "CM_PROD"
    final def COLUMN_CM_PRODUCTION = "CM_PRODUCTION"

    final def COLUMN_CM_ACNDEVOPS = "CM_ACNDEVOPS"


    final def SEPARATOR_COMMA = ","
    final def SEPARATOR_SEMICOLUMN = ";"

    final def CSV_STATIC_HEADER = [COLUMN_ID, COLUMN_PHASE, COLUMN_ENVIRONMENT, COLUMN_FILENAME, COLUMN_HAS_ATTACHMENTS, COLUMN_REQUESTER, COLUMN_DURATION]

    private def dsl
    private def actualSeparator
    private def header


    CsvUtils(def dsl) {
        this.dsl = dsl
    }

    @NonCPS
    private void setActualSeparator(String firstLine) {
        def indexComma = firstLine.indexOf(SEPARATOR_COMMA)
        if (indexComma > 0) {
            actualSeparator = SEPARATOR_COMMA
        } else {
            actualSeparator = SEPARATOR_SEMICOLUMN
        }
    }

    def initHeader(def devEnvironments) {
        if (!header) {
            dsl.echo("initHeader: devEnvironments->" + devEnvironments)
            assert (devEnvironments.size() > 0)
            def envHeader = []
            devEnvironments.each { environment ->
                def env = environment.trim().toUpperCase()
                envHeader.add("CM_${env}")
            }
            header = CSV_STATIC_HEADER + envHeader
        } else {
            dsl.echo("header already initialized. Skipping initialization")
        }

        return header
    }

    def getHeader() {
        return header
    }

    def reclaimCsv(def rawCsvTextLines) {
        boolean cm_acndevops_found
        def warnings = []
        String firstLine = rawCsvTextLines[0]

        setActualSeparator(firstLine)

        // header fix
        // fix for "CM_PRODUCTION" vs. "CM_PROD"
        firstLine = firstLine.replace(COLUMN_CM_PRODUCTION, COLUMN_CM_PROD)

        // fix for missing column "CM_ACNDEVOPS"
        if (COLUMN_CM_ACNDEVOPS in header) {
            int cm_acndevops_idx = firstLine.indexOf(COLUMN_CM_ACNDEVOPS)
            cm_acndevops_found = (cm_acndevops_idx > -1)
            if (!cm_acndevops_found) {
                firstLine = "${firstLine}${actualSeparator}${COLUMN_CM_ACNDEVOPS}"
            }
            rawCsvTextLines[0] = firstLine
        }

        // fix lines without "CM_ACNDEVOPS" and "null" values
        def headerList = firstLine.split(actualSeparator)

        for (i in 1..<rawCsvTextLines.size()) {
            String line = rawCsvTextLines[i]

            def lineCells = line.split(actualSeparator, -1)

            // fix cell count
            int missingCells = (headerList.size() - lineCells.size())
            if (missingCells < 0) {
                warnings.add("WARNING: there are more cells (${lineCells.size()}) than headers (${headerList.size()}) on line \"${line}\"")
            }
            if (missingCells > 1) {
                warnings.add("WARNING: too many missing cells (${missingCells})")
            }

            for (j in 0..<missingCells) {
                warnings.add("WARNING: Adding separator \"${actualSeparator}\" to line ${line}")
                lineCells = lineCells + [""]
            }

            // fix invalid char in column "CM_*"
            for (j in 7..<lineCells.size()) {
                String cellValue = lineCells[j].toString().toUpperCase()
                if (!(cellValue == "" || cellValue == "X" || cellValue == "-")) {
                    warnings.add("WARNING: Replacing invalid cell value \"${lineCells[j].toString()}\" with empty string")
                    lineCells[j] = ""
                }
            }

            rawCsvTextLines[i] = lineCells.join(actualSeparator)
        }

        if (warnings.size() > 0) {
            dsl.echo(warnings.join("\n"))
        }

        return rawCsvTextLines
    }

    def read(String csvFileName, def devEnvironments) {
        initHeader(devEnvironments)

        def csvFile = dsl.readFile csvFileName
        def rawCsvTextLines = csvFile.readLines().findAll { it != "" }
        dsl.echo("rawCsvTextLines->\n" + rawCsvTextLines.join("\n") + "\n")

        // fix invalid column like "CM_PRODUCTION" vs. "CM_PROD" and missing "CM_ACNDEVOPS"
        def csvTextLines = reclaimCsv(rawCsvTextLines)
        dsl.echo("csvTextLines->\n" + csvTextLines.join("\n") + "\n")

        //CSVFormat csvFormat = CSVFormat.newFormat(actualSeparator as char).withFirstRecordAsHeader()

        dsl.echo("new csvFormat")
        CSVFormat csvFormat = CSVFormat.newFormat(actualSeparator as char).builder.setHeader().setSkipHeaderRecord(false)
        
        def content = dsl.readCSV text: csvTextLines.join("\n"), format: csvFormat
        boolean colOk = checkColumns(content, header)
        if (!colOk) {
            dsl.error("Error in csv column definition")
        }

        return content
    }

    void createManualproceduresCsv(String path) {
        assert header: "Header not initialized"
        String csvHeaders = header.join(",") + "\n"
        dsl.writeFile file: path, text: csvHeaders
    }

    boolean checkColumns(def content, header) {
        def colsOk = true

        if (content[0]) {
            dsl.echo("content[0] --> " +  content[0])
            def contentHeader = content[0].mapping

            def headerList = (contentHeader.keySet() as ArrayList)[0].split(actualSeparator)

            headerList.each { headerCol ->
                if (colsOk) {
                    def col = header.find { it == headerCol }
                    if (!col) {
                        colsOk = false
                        dsl.error("Header column ${headerCol} not found in columns definitions")
                    }
                }
            }
        } else {
            colsOk = true
        }

        return colsOk
    }

    def getRecords(def content, String phase, String environment) {
        def records = []

        content.each { line ->
            try {
                String env = line.get(COLUMN_ENVIRONMENT).toUpperCase()
                String ph = line.get(COLUMN_PHASE)
                def envList = env.split("\\|")
                def getHasToBeExecutedInEnvStr = line.get("CM_${environment.toUpperCase()}")
                boolean getHasToBeExecutedInEnv = "".equals(getHasToBeExecutedInEnvStr.toUpperCase())
                if ((environment in envList || "ALL" in envList) && phase.equals(ph) && getHasToBeExecutedInEnv) {
                    records.add(line)
                }
            } catch (e) {
                dsl.echo(e.message)
            }
        }

        return records
    }

    def getRecordsDescriptionWithFileName(String mpfileName, def content) {
        def descrLines = []

        descrLines.add("---- ${mpfileName} ----")
        content.eachWithIndex { line, idx ->
            try {
                def id = line.get(COLUMN_ID)
                def ph = line.get(COLUMN_PHASE)
                def env = line.get(COLUMN_ENVIRONMENT)
                def fileName = line.get(COLUMN_FILENAME)
                def hasAttachment = line.get(COLUMN_HAS_ATTACHMENTS)

                descrLines.add("\t[${idx}] - id: ${id}, environments: ${env}, phase: ${ph}, hasAttachment: ${hasAttachment}, filename: ${fileName}")
            } catch (e) {
                dsl.echo(e.message)
            }
        }
        descrLines.add("")

        return descrLines.join("\n")
    }

    private def getColumnValue(def record, String column) {
        def value
        try {
            value = record.get(column)
        } catch (e) {
            dsl.echo("Record->" + record + "\n" + e.message)
        }

        return value
    }

    def getRecordFilename(def record) {
        return getColumnValue(record, COLUMN_FILENAME)
    }

    def getRecordPhase(def record) {
        return getColumnValue(record, COLUMN_PHASE)
    }

    def getRecordHasAttachment(def record) {
        def hasAttachment = getColumnValue(record, COLUMN_HAS_ATTACHMENTS)
        return "TRUE".equals(hasAttachment.toUpperCase())
    }

    def getRecordRequester(def record) {
        return getColumnValue(record, COLUMN_REQUESTER)
    }

    def getRecordId(def record) {
        return getColumnValue(record, COLUMN_ID)
    }

    def getRecordDuration(def record) {
        return getColumnValue(record, COLUMN_DURATION)
    }

    def getRecordEnvironment(def record) {
        return getColumnValue(record, COLUMN_ENVIRONMENT)
    }

    def getRecordExecutionEnv(def record, String environment) {
        assert environment
        def env = environment.trim().toUpperCase()
        assert (!environment.trim().equals(""))
        return getColumnValue(record, "CM_${env}")
    }

    def getHasToBeExecutedInEnv(def record, String environment) {
        boolean result = true

        try {
            String env = record.get(COLUMN_ENVIRONMENT).toUpperCase()
            def envList = env.split("\\|")
            if (environment in envList || "ALL" in envList) {
                // TODO controllare i casi validi "X", "-", ""
                def executionMark = record.get("CM_${environment.toUpperCase()}")
                result = "".equals(executionMark)
            } else {
                result = false
            }
        } catch (e) {
            dsl.echo(e.message)
        }

        return result
    }

    String getHeaderString() {
        String header = header.join(",")
        return header
    }

    def isExecutionPhaseValid(def record) {
        boolean result = false

        try {
            def executionPhase = record.get(COLUMN_PHASE)
            String exPhase = executionPhase.toUpperCase()

            if (STAGE_PRE.equals(exPhase) || STAGE_POST.equals(exPhase)) {
                result = true
            } else {
                dsl.echo("Invalid execution phase \"${exPhase}\" for record " + record)
            }
        } catch (e) {
            dsl.echo(e.message)
        }

        return result
    }

    void writeManualProcedureCsv(String nameFileCSV, def records, def devEnvironments) {
        initHeader(devEnvironments)

        String[] array = new ArrayList<>(header)
        CSVFormat csvFormat = CSVFormat.EXCEL.withHeader(array)

        dsl.writeCSV file: nameFileCSV, records: records, format: csvFormat
    }

}
