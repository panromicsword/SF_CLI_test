package it.sky.dp.jenkins.salesforce.technology

import com.cloudbees.groovy.cps.NonCPS

class CsvSalesforceUtils implements Serializable {

  final String COLUMN_ID = "ID"
  final String COLUMN_PHASE = "PRE/POST"
  final String COLUMN_ENVIRONMENT = "ENVIRONMENT"
  final String COLUMN_FILENAME = "FILENAME"
  final String COLUMN_HAS_ATTACHMENTS = "HAS_ATTACHMENTS"
  final String COLUMN_REQUESTER = "REQUESTER"
  final String COLUMN_DURATION = "DURATION"

  final String SEPARATOR_COMMA = ","
  final String SEPARATOR_SEMICOLUMN = ";"

  final def COLUMN_CM_PROD = "CM_PROD"
  final def COLUMN_CM_PRODUCTION = "CM_PRODUCTION"

  final def COLUMN_CM_ACNDEVOPS = "CM_ACNDEVOPS"

  final String [] CSV_STATIC_HEADER = [COLUMN_ID, COLUMN_PHASE, COLUMN_ENVIRONMENT, COLUMN_FILENAME, COLUMN_HAS_ATTACHMENTS, COLUMN_REQUESTER, COLUMN_DURATION]
  //ID, PRE/POST, ENVIRONMENT, FILENAME, HAS_ATTACHMENTS, REQUESTER, DURATION, CM_AM, CM_IT, CM_UAT, CM_TST5, CM_ST, CM_DEVE2E, CM_COURTESY, CM_PT, CM_CATALOG, CM_ACNDEVOPS, CM_PRODID, PRE/POST, ENVIRONMENT, FILENAME, HAS_ATTACHMENTS, REQUESTER, DURATION, CM_AM, CM_IT, CM_UAT, CM_TST5, CM_ST, CM_DEVE2E, CM_COURTESY, CM_PT, CM_CATALOG, CM_ACNDEVOPS, CM_PROD

  private def dsl
  private String currentSeparator
  private String currentSubSeparator
  private def header
  private ArrayList<ArrayList<String>> csvContainer = new ArrayList<>()

  CsvSalesforceUtils(def dsl) {
    this.dsl = dsl
  }

  @NonCPS
  private String setCurrentSeparator(String firstLine) {
    String sep = null
    def indexComma = firstLine.indexOf(SEPARATOR_COMMA)
    if (indexComma > 0) {
      sep = SEPARATOR_COMMA
    } else {
      sep = SEPARATOR_SEMICOLUMN
    }
    return sep
  }

  def initHeader(def devEnvironments) {
    dsl.echo("initHeader: devEnvironments -> " + devEnvironments)
    assert (devEnvironments.size() > 0)

    def envHeader = []
    devEnvironments.each { environment ->
      String env = environment.trim().toUpperCase()
      envHeader.add("CM_${env}")
    }
    def internalHeader = CSV_STATIC_HEADER + envHeader
    
    return internalHeader
  }

  def setUpHeader(def headers) {
    if (csvContainer && csvContainer.size() > 0) {
      dsl.echo("Headers class already set-up")
    } else { 
      ArrayList<String> headersList = new ArrayList<>()
      headers.each { 
        headersList.push(it.trim())
      }
      csvContainer.push(headersList)
      dsl.echo("Header class initialized.\n${csvContainer.get(0)}\n${csvContainer.get(0).size()}")  
    }
  }

  def getHeader() {
    return header
  }

  String [] reclaimCsv(String [] rawCsvTextLines) {
    boolean cm_acndevops_found
    def warnings = []
    String firstLine = rawCsvTextLines[0]

    currentSeparator = setCurrentSeparator(firstLine)

    String [] headerList = firstLine.split(currentSeparator)
    
    for (i in 1..<rawCsvTextLines.size()) {
      String line = rawCsvTextLines[i]

      def lineCells = line.split(currentSeparator, -1)

      // fix cell count
      int missingCells = (headerList.size() - lineCells.size())
      if (missingCells < 0) {
        warnings.add("there are more cells (${lineCells.size()}) than headers (${headerList.size()}) on line \"${line}\"")
      }
      if (missingCells > 1) {
        warnings.add("too many missing cells (${missingCells})")
      }

      for (j in 0..<missingCells) {
        warnings.add("Adding separator \"${currentSeparator}\" to line ${line}")
        lineCells = lineCells + [""]
      }

      // fix invalid char in column "CM_*"
      for (j in 7..<lineCells.size()) {
        String cellValue = lineCells[j].toString().toUpperCase()
        if (!(cellValue == "" || cellValue == "X" || cellValue == "-")) {
          warnings.add("Replacing invalid cell value \"${lineCells[j].toString()}\" with empty string")
          lineCells[j] = ""
        }
      }

      rawCsvTextLines[i] = lineCells.join(currentSeparator)
    }

    if (warnings.size() > 0) {
      dsl.echo(warnings.join("\n"))
    }

    return rawCsvTextLines
  }

  ArrayList<ArrayList<String>> read( String csvFileName, def devEnvironments, boolean clearContainer) {
    if (clearContainer) {
      csvContainer.clear()
    }

    header = initHeader(devEnvironments)

    def csvFile = dsl.readFile csvFileName
    String [] rawCsvTextLines = csvFile.readLines().findAll { it != "" }

    String [] csvTextLines = reclaimCsv(rawCsvTextLines)

    csvContainer = mapHeader(csvTextLines[0].replace(COLUMN_CM_PRODUCTION, COLUMN_CM_PROD).split(currentSeparator))

    for (int i = 1; i < csvTextLines.size(); i++) {
      this.currentSubSeparator = setCurrentSeparator(csvTextLines[i])
      ArrayList<String> csvLine = csvTextLines[i].replaceAll(/]|\[/, "").split(currentSubSeparator, -1)
      csvContainer.push(csvLine)
    }

    boolean colOk = checkColumns(header)
    if (!colOk) {
      dsl.error("Error in csv column definition")
    }
    return csvContainer
  }

  boolean checkColumns(header) {
    def colsOk = true

    csvContainer.get(0).each { headerCol ->
      if (colsOk) {
        def col = header.find { it == headerCol }
        if (!col) {
          colsOk = false
          dsl.error("Header column ${headerCol} not found in columns definitions!")
        }
      }
    }

    return colsOk
  }

  ArrayList<ArrayList<String>> getRecords(Map<String, ArrayList<ArrayList<String>>> content, String phase, String environment) {
    ArrayList<ArrayList<String>> records = new ArrayList<>()
    dsl.echo("-"*80 + "\nCollecting records for phase '${phase}' on environment '${environment}':\n${content}\n" + "-"*80)
    content.values().each {cont ->
      for (int i = 1; i < cont.size(); i++) {
        ArrayList<String> record = cont.get(i)
        try {
          String fileName = record.get(getHeaderIdx(COLUMN_FILENAME))
          String cmEnv = "CM_${environment.toUpperCase().trim()}"
          String env = record.get(getHeaderIdx(COLUMN_ENVIRONMENT)).toUpperCase()
          String ph = record.get(getHeaderIdx(COLUMN_PHASE))
          String[] envList = env.toString().split("\\|")
          String getHasToBeExecutedInEnvStr = record.get(getHeaderIdx(cmEnv))
          boolean getHasToBeExecutedInEnv = "".equals(getHasToBeExecutedInEnvStr.toUpperCase())
          dsl.echo("""
          fileName: ${fileName}
          cmEnv: '${cmEnv}' - env: '${env}' | environment: '${environment}' -> in envList: ${environment in envList} 
          phase: '${phase}' - ph: '${ph}'   | TBE(Column:'${getHasToBeExecutedInEnvStr}') -> '${getHasToBeExecutedInEnv}'
          environment in envList: ${environment in envList} | "ALL" in envList: ${"ALL" in envList} | phase.equals(ph): ${phase.equals(ph)}
          (environment in envList || "ALL" in envList) && phase.equals(ph) && getHasToBeExecutedInEnv: ${(environment in envList || "ALL" in envList) && phase.equals(ph) && getHasToBeExecutedInEnv}
        """)
          if ((environment in envList || "ALL" in envList) && phase.equals(ph) && getHasToBeExecutedInEnv) {
            dsl.echo("Adding record to Manual procedure list: ${cmEnv} - env(${getHeaderIdx(COLUMN_ENVIRONMENT)}): ${env} - ph(${getHeaderIdx(COLUMN_PHASE)}): ${ph}")
            records.push(record)
          }
        } catch (e) {
          dsl.echo("Error on getRecords() method: ${e.message}")
        }
      }
      dsl.echo("-"*80 + "\nEnd collecting records for phase '${phase}' on environment '${environment}':\n${content}\n" + "-"*80)
    }
    return records
  }

  def getRecordsDescriptionWithFileName(String mpfileName, def content, Integer counter) {
    def descrLines = []

    descrLines.add("---- ${mpfileName} ----")

      try {
        //String [] values =  line.values[0].toString().replaceAll(/]|\[/, "").split(SEPARATOR_SEMICOLUMN, -1)
        String id = content.get(getHeaderIdx(COLUMN_ID))
        String ph = content.get(getHeaderIdx(COLUMN_PHASE))
        String env = content.get(getHeaderIdx(COLUMN_ENVIRONMENT))
        String fileName = content.get(getHeaderIdx(COLUMN_FILENAME))
        String hasAttachment = content.get(getHeaderIdx(COLUMN_HAS_ATTACHMENTS))

        descrLines.add("[${counter}] - id: ${id}, environments: ${env}, phase: ${ph}, hasAttachment: ${hasAttachment}, filename: ${fileName}")
      } catch (e) {
        dsl.echo("Error on getRecordsDescriptionWithFileName() method: ${e.message}\n${e.getStackTrace()}")
      }

    descrLines.add("")

    return descrLines.join("\n")
  }

  private String getColumnValue(ArrayList<String> record, String column) {
    String value = null
    try {
      Integer idx = getHeaderIdx(column)

      value = record.get(idx)

    } catch (e) {
      dsl.echo("Error on getColumnValue(${record}, ${column}), check if all the columns are set properly on the csv manual procedures file. method: ${e.message}")
    }

    return value
  }

  def getRecordFilename(ArrayList<String> record) {
    return getColumnValue(record, COLUMN_FILENAME)
  }

  def getRecordPhase(ArrayList<String> record) {
    return getColumnValue(record, COLUMN_PHASE)
  }

  def getRecordHasAttachment(ArrayList<String> record) {
    def hasAttachment = getColumnValue(record, COLUMN_HAS_ATTACHMENTS)
    return "TRUE".equals(hasAttachment.toUpperCase())
  }

  def getRecordDuration(ArrayList<String> record) {
    return getColumnValue(record, COLUMN_DURATION)
  }

  def getRecordId(ArrayList<String> record) {
    return getColumnValue(record, COLUMN_ID)
  }

  def getRecordEnvironment(ArrayList<String> record) {
    return getColumnValue(record, COLUMN_ENVIRONMENT)
  }

  def getRecordRequester(ArrayList<String> record) {
    return getColumnValue(record, COLUMN_REQUESTER)
  }

  def getRecordExecutionEnv(ArrayList<String> record, String environment) {
    assert environment
    def env = environment.trim().toUpperCase()
    assert (!environment.trim().equals(""))
    return getColumnValue(record, "CM_${env}")
  }


  def getHasToBeExecutedInEnv(ArrayList<String> record, String environment) {
    boolean result = true

    try {
      def env = record.get(getHeaderIdx(COLUMN_ENVIRONMENT)).toUpperCase()
      def envList = env.split("\\|")

      if (environment in envList || "ALL" in envList) {

        String executionMark
        String cmEnv = "CM_${environment.toUpperCase()}"
        try {

          executionMark = record.get(getHeaderIdx(cmEnv))
        } catch (Exception ignored) {
          executionMark = ""
        }
        result = "".equals(executionMark)
      } else {
        result = false
      }
    } catch (e) {
      dsl.echo("Error on getHasToBeExecutedInEnv() method: ${e.message}")
    }

    return result
  }


  def isExecutionPhaseValid(ArrayList<String> record) {
    boolean result = false

    try {
      String executionPhase = record.get(getHeaderIdx(COLUMN_PHASE))
      String exPhase = executionPhase.toUpperCase()

      if ("PRE".equals(exPhase) || "POST".equals(exPhase)) {
        result = true
      } else {
        dsl.echo("Invalid execution phase \"${exPhase}\" for record " + record)
      }
    } catch (e) {
      dsl.echo("Error on isExecutionPhaseValid() method: ${e.message}")
    }

    return result
  }

  ArrayList<ArrayList<String>> mapHeader(String[] csvHeader) {
    ArrayList<String> headerMapIdx = new ArrayList<>()
    ArrayList<ArrayList<String>> container = new ArrayList<>()

    for (int i = 0; i < csvHeader.size(); i++) {
      headerMapIdx.push(csvHeader[i])
    }
    container.push(headerMapIdx)
    return container
  }

  Integer getHeaderIdx(String value){
    Integer i = csvContainer.get(0).indexOf(value)

    return i
  }

  String getCurrentSeparator() {
    return currentSeparator
  }

  void writeManualProcedureCsv(String nameFileCSV, ArrayList<ArrayList<String>> records, def devEnvironments) {
    header =initHeader(devEnvironments)
    List<String> csv = new ArrayList<>()
    String headerString = header[0]
    for (int i=1; i < header.size(); i++) {
      headerString += "," + header[i]
    }
    csv.push(headerString)
    records.each {
      String recordString = it[0]
      for (int i=1; i < it.size(); i++) {
        recordString += "," + it[i]
      }
      csv.push(recordString)
    }

    dsl.writeFile(file: nameFileCSV, text: csv.join("\n"))

  }

  void writeAlignmentManualProcedureCsv(String nameFileCSV, ArrayList<ArrayList<String>> records, def devEnvironments) {
    //initHeader(devEnvironments)
    //List<String> csv = new ArrayList<>()
    //csv.push(csvContainer.get(0).join(currentSeparator))
    //records.each {
    //  csv.push(it.join(currentSubSeparator))
    //}

    dsl.writeFile(file: nameFileCSV, text: records.join("\n"))

  }

  void createManualproceduresCsv(String path, def envs) {
  header = initHeader(envs)
  assert header: "Header not initialized"
  String csvHeaders = header.join(",") + "\n"
  dsl.writeFile file: path, text: csvHeaders
  
  }
}

