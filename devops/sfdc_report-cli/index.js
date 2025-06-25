#!/usr/bin/env node

const jsforce = require('jsforce');
const Q = require('q');
const sleep = require('system-sleep');
const ExcelJS = require('exceljs');
const args = require('minimist')(process.argv.slice(2));
const fs = require('fs');

///////////////////////////////////////////////////////////////////////////////

// input variables
var username = process.env.SLFC_USERNAME;
var password = process.env.SLFC_PASSWORD;
var loginUrl = 'https://' + process.env.SLFC_URL;
var version = process.env.SLFC_VERSION;

var deployId = args.deployId;
var reportFile = args.reportFile;
var reportPMD = args.reportPMD;
var reportManualProcedures = args.reportManualProcedures;
var reportInfoCommit = args.reportInfoCommit;
var reportVlocityComponents = args.reportVlocityComponents;

// jsforce variables
var sfdc_loggedIn = false;
var sfdc_client = null;
var deployStatus = null;

// PMD variables
var PMDobj = null;

// input variables
var inputsObj = null;
var manualObj = null;
var infoObj = null;
var vlocityObj = null;

var workbook = null;

const SHEET_RECAP = 'Recap';
const SHEET_VALIDATED_COMPONENTS = 'Validated components';
const SHEET_COMPONENT_ERRORS = 'Component errors';
const SHEET_TEST_FAILURES = 'Test Failures';
const SHEET_TEST_EXECUTION = 'Test Execution';
const SHEET_PMD_REPORT = 'PMD Report';
const SHEET_MANUAL_PROCEDURES = 'Manual Procedures';
const SHEET_VLOCITY_CATALOG = 'Vlocity Catalog';

const MONITORING_JSON_FILENAME = 'monitoring.json';

const NOT_AVAILABLE_STR = 'NA';

///////////////////////////////////////////////////////////////////////////////

var validateInputs = async function () {
    'use strict';

    var inputOK = true;

    console.log(loginUrl);
    console.log(version);
    console.log(deployId);
    console.log(reportFile);
    console.log(reportPMD);
    console.log(reportInfoCommit);
    console.log(reportVlocityComponents);

    if (username === null || username === '' || username === undefined ||
        password === null || password === '' || password === undefined ||
        loginUrl === null || loginUrl === '' || loginUrl === undefined ||
        version === null || version === '' || version === undefined ||
        reportFile === null || reportFile === '' || reportFile === undefined) {

        console.error('Input error');
        inputOK = false;
    } else {
        console.log('All input are present');
    }

    return inputOK;
};

var sfdcLogin = async function () {
    'use strict';

    console.log('### sfdcLogin started ###');
    var deferred = Q.defer();

    if (deployId === null || deployId === '' || deployId === undefined) {
        console.log('Login not necessary because NO validation has been executed.');
    } else {
        if (validateInputs() ) {
            console.log('Logging in as ' + username);
    
            sfdc_client = new jsforce.Connection({ loginUrl: loginUrl, version: version });
    
            sfdc_client.login(username, password, function (error, res) {
                if (error) {
                    console.log('Error Occurred: ' + error);
                    deferred.reject(new Error(error));
                } else {
                    sfdc_loggedIn = true;
                    console.log('Logged in');
                    deferred.resolve();
                }
            });
        } else {
            deferred.reject(new Error("an input is null or empty"));
        } 
    }

    return deferred.promise;
};

var sfdcLogout = async function () {
    'use strict';

    console.log('### sfdcLogout started ###');

    var deferred = Q.defer();

    if (deployId === null || deployId === '' || deployId === undefined) {
        console.log('Logout not necessary because NO validation has been executed.');
    } else {
        if (sfdc_client != null ) {
            sfdc_client.logout(function (error, res) {
                if (error) {
                    console.log('Error Occurred: ' + error);
                    deferred.reject(new Error(error));
                } else {
                    console.log('Logged out');
                    deferred.resolve();
                }
            });
        } else {
            deferred.resolve();
        }
    }

    return deferred.promise;
};

var sfdcCheckDeployStatus = async function () {
    'use strict';

    console.log('### sfdcCheckDeployStatus started ###');

    var deferred = Q.defer();

    if (deployId === null || deployId === '' || deployId === undefined) {
        console.log('DeployId not found. No validation has been executed.');
    } else {
        var counter = 0;
        while (!sfdc_loggedIn && counter <= 120) {
            sleep(500);
            counter++;
        }

        if (sfdc_client != null && sfdc_loggedIn) {
            let result = sfdc_client.metadata.checkDeployStatus(deployId, true);
            result.then(ok => {
                deployStatus = ok;

                deferred.resolve();
            })
                .catch(error => {
                    console.log('Error Occurred: ' + error);
                    deferred.reject(new Error(error));
                });
        } else {
            deferred.reject(new Error('sfdc_client is null or sfdc_loggedIn is false'));
        }
    }

    return deferred.promise;
};

var readInputsJson = async function () {
    console.log('### readInputsJson started ###');

    var deferred = Q.defer();

    try {
        const data = fs.readFileSync('inputParams.json', 'utf8');
        inputsObj = JSON.parse(data);

        deferred.resolve();
    } catch (err) {
        deferred.reject(new Error(err));
    }

    return deferred.promise;
};

var readManualJson = async function () {
    console.log('### readManualJson started ###');

    var deferred = Q.defer();

    try {
        if (reportManualProcedures === undefined) {
            console.log('Manual procedures not Defined');
        } else {
            const data = fs.readFileSync(reportManualProcedures, 'utf8');
            manualObj = JSON.parse(data);
        }

        deferred.resolve();
    } catch (err) {
        deferred.reject(new Error(err));
    }

    return deferred.promise;
};

var readInfoCommitJson = async function () {
    console.log('### readInfoCommit started ###');

    var deferred = Q.defer();

    try {
        if (reportInfoCommit === undefined) {
            console.log('Info Commit not Defined');
        } else {
            const data = fs.readFileSync(reportInfoCommit, 'utf8');
            infoObj = JSON.parse(data);
        }

        deferred.resolve();
    } catch (err) {
        deferred.reject(new Error(err));
    }

    return deferred.promise;
};

var readVlocityComponetsJson = async function () {
    console.log('### readVlocityComponets started ###');

    var deferred = Q.defer();

    try {
        if (reportVlocityComponents === undefined) {
            console.log('Info Vlocity Components not Defined');
        } else {
            const data = fs.readFileSync(reportVlocityComponents, 'utf8');
            vlocityObj = JSON.parse(data);
        }

        deferred.resolve();
    } catch (err) {
        deferred.reject(new Error(err));
    }

    return deferred.promise;
};

var readPMDJson = async function () {
    console.log('### readPMDJson started ###');

    var deferred = Q.defer();

    try {
        if (reportPMD === undefined) {
            console.log('PMD not Defined');
        } else {
            const data = fs.readFileSync(reportPMD, 'utf8');
            PMDobj = JSON.parse(data);
        }
        deferred.resolve();
    } catch (err) {
        deferred.reject(new Error(err));
    }

    return deferred.promise;
};

var createXlsFile = async function () {
    'use strict';

    console.log('### createXlsFile started ###');

    var deferred = Q.defer();

    workbook = new ExcelJS.Workbook();
    workbook.creator = 'Jenkins';
    workbook.lastModifiedBy = 'Jenkins';
    workbook.created = new Date();
    workbook.modified = new Date();

    console.log("Adding sheet " + SHEET_RECAP);

    const mainSheet = workbook.addWorksheet(SHEET_RECAP, { views: [{ showGridLines: false }] });
    if (manualObj && manualObj.manualProcedures) {
        mainSheet.getCell('A19' ).value = 'MANUAL PROCEDURES - PRODUCTION VIEW:';
        mainSheet.getCell('A20').value = '# pre manual procedures to execute:';
        mainSheet.getCell('A21').value = '# post manual procedures to execute:';
        mainSheet.getCell('A22').value = '# total manual procedures to execute:';
        mainSheet.getCell('A23').value = '# pre duration to execute:';
        mainSheet.getCell('A24').value = '# post duration to execute:';
        mainSheet.getCell('A25').value = '# total duration to execute:';

        var i = 20; // first xls line
        for (var k in manualObj.manualProcedures) {
            const row = mainSheet.getRow(i);
            row.getCell(2).value = manualObj.manualProcedures[k];
            i++;
        }
    }

    if (inputsObj) {
        mainSheet.getCell('E1').value = 'INPUT PARAMETERS:';

        var ipCounter = 2;
        for (var attributename in inputsObj) {
            mainSheet.getCell('E' + ipCounter).value = attributename;
            mainSheet.getCell('F' + ipCounter).value = inputsObj[attributename];
            ipCounter++;
        }
    }

    mainSheet.eachRow({ includeEmpty: false }, function (row, rowNumber) {
        var numCell;
        if (manualObj && manualObj.manualProcedures) {
            numCell = 27;
        } else {
            numCell = 19;
        }
        row.eachCell(function (cell, colNumber) {
            if (colNumber == 1 && rowNumber <= numCell) {
                cell.font = {
                    bold: true,
                };
            }
            if (colNumber == 2) {
                cell.alignment = {
                    horizontal: 'center'
                };
            }
            if (colNumber == 5) {
                cell.font = {
                    bold: true,
                };
            }
            if (colNumber == 6) {
                cell.alignment = {
                    horizontal: 'center'
                };
            }
        });
    });

    mainSheet.getColumn(1).width = 40;
    mainSheet.getColumn(2).width = 32;
    mainSheet.getColumn(5).width = 32;
    mainSheet.getColumn(6).width = 32;

    if (deployId === null || deployId === '' || deployId === undefined) {
        console.log('No Validation has been executed. Creation of SFDC Report with eventually Manual Procedures and Vlocity');
    } else {
        var counter = 0;
        while (!deployStatus && counter <= 120) {
            sleep(500);
            counter++;
        }

        if (deployStatus && deployStatus.details) {
            // debug
            try {
                fs.writeFileSync('deployStatus.json', JSON.stringify(deployStatus));
            } catch (err) {
                console.error(err);
            }

            mainSheet.getCell('A1').value = 'Deploy ID:';
            mainSheet.getCell('B1').value = deployId;
            mainSheet.getCell('A2').value = 'Check Only:';
            mainSheet.getCell('B2').value = deployStatus.checkOnly;
            mainSheet.getCell('A3').value = 'Validation DateTime:';
            mainSheet.getCell('B3').value = deployStatus.completedDate;
            mainSheet.getCell('A4').value = 'Status:';
            mainSheet.getCell('B4').value = deployStatus.status;

            mainSheet.getCell('A6').value = 'COMPONENTS:';
            mainSheet.getCell('A7').value = '# components total:';
            mainSheet.getCell('B7').value = deployStatus.numberComponentsTotal;
            mainSheet.getCell('A8').value = '# components deployed:';
            mainSheet.getCell('B8').value = deployStatus.numberComponentsDeployed;
            mainSheet.getCell('A9').value = '# component errors:';
            mainSheet.getCell('B9').value = deployStatus.numberComponentErrors;

            mainSheet.getCell('A11').value = 'TESTS:';
            mainSheet.getCell('A12').value = '# tests total:';
            mainSheet.getCell('A13').value = '# tests completed:';
            mainSheet.getCell('A14').value = '# test errors:';
            if (deployStatus.runTestsEnabled) {
                mainSheet.getCell('B12').value = (deployStatus.numberTestsTotal == 0) ? NOT_AVAILABLE_STR : deployStatus.numberTestsTotal;
                mainSheet.getCell('B13').value = (deployStatus.numberTestsCompleted == 0) ? NOT_AVAILABLE_STR : deployStatus.numberTestsCompleted;
                mainSheet.getCell('B14').value = (deployStatus.numberTestErrors == 0) ? NOT_AVAILABLE_STR : deployStatus.numberTestErrors;
            } else {
                mainSheet.getCell('B12').value = NOT_AVAILABLE_STR;
                mainSheet.getCell('B13').value = NOT_AVAILABLE_STR;
                mainSheet.getCell('B14').value = NOT_AVAILABLE_STR;
            }

            mainSheet.getCell('A15').value = '# average code coverage:';
            mainSheet.getCell('A16').value = '# min code coverage:';
            if (deployStatus.details.runTestResult && deployStatus.details.runTestResult.codeCoverage) {
                var totalNotCoverdLines = 0;
                var totalLines = 0;
                var minCodeCoverage = 0;
                var totalAverage = 0;

                if (Array.isArray(deployStatus.details.runTestResult.codeCoverage)) {
                    deployStatus.details.runTestResult.codeCoverage.forEach(cc => {
                        var numLocationsNotCovered = Number(cc.numLocationsNotCovered);
                        var numTotalLines = Number(cc.numLocations);
                        var codeCoveragePercentage = 0;
                        if (numTotalLines > 0) {
                            codeCoveragePercentage = Number((1 - (numLocationsNotCovered / numTotalLines)));
                        }

                        if (minCodeCoverage > codeCoveragePercentage) {
                            minCodeCoverage = codeCoveragePercentage;
                        }

                        totalNotCoverdLines = totalNotCoverdLines + numLocationsNotCovered;
                        totalLines = totalLines + numTotalLines;
                    });
                } else {
                    totalNotCoverdLines = deployStatus.details.runTestResult.codeCoverage.numLocationsNotCovered;
                    totalLines = deployStatus.details.runTestResult.codeCoverage.numLocations;
                    if (totalLines > 0) {
                        minCodeCoverage = Number((1 - (totalNotCoverdLines / totalLines)));
                    }
                }

                if (totalLines > 0) {
                    totalAverage = (1 - (totalNotCoverdLines / totalLines));
                }

                mainSheet.getCell('B15').value = totalAverage;
                mainSheet.getCell('B15').numFmt = '0.00%';
                mainSheet.getCell('B16').value = minCodeCoverage;
                mainSheet.getCell('B16').numFmt = '0.00%';
            } else {
                mainSheet.getCell('B15').value = NOT_AVAILABLE_STR;
                mainSheet.getCell('B16').value = NOT_AVAILABLE_STR;
            }

            // test class not covered
            mainSheet.getCell('A17').value = '# class not covered:';
            if (deployStatus.details.runTestResult && deployStatus.details.runTestResult.codeCoverageWarnings) {
                var classNotCovered = 0;

                if (Array.isArray(deployStatus.details.runTestResult.codeCoverageWarnings)) {
                    deployStatus.details.runTestResult.codeCoverageWarnings.forEach(cc => {
                        if (cc.message.indexOf(' 0%') > -1) {
                            classNotCovered++;
                        }
                    });
                } else {
                    if (deployStatus.details.runTestResult.codeCoverageWarnings.message.indexOf(' 0%') > -1) {
                        classNotCovered++;
                    }
                }

                mainSheet.getCell('B17').value = classNotCovered;
            } else {
                mainSheet.getCell('B17').value = NOT_AVAILABLE_STR;
            }

            // pivot compenent validated
            if (deployStatus.details.componentSuccesses) {
                const infoComponentMap = new Map();
                if (Array.isArray(deployStatus.details.componentSuccesses)) {
                    deployStatus.details.componentSuccesses.forEach(component => {
                        var componentCount = infoComponentMap.get(component.componentType)
                        if (componentCount == null) {
                            componentCount = 1;
                        } else {
                            componentCount++;
                        }
                        if (component.componentType != "") {
                            infoComponentMap.set(component.componentType, componentCount);
                        }  
                    });
                } else {
                    //se non è un array
                    var componentCount = infoComponentMap.get(deployStatus.details.componentSuccesses.componentType)
                    if (componentCount == null) {
                        componentCount = 1;
                    } else {
                        componentCount++;
                    }
                    if (deployStatus.details.componentSuccesses.componentType != "") {
                        infoComponentMap.set(deployStatus.details.componentSuccesses, componentCount);
                    }
                }
                if (infoComponentMap && infoComponentMap.size > 0) {
                    if (manualObj && manualObj.manualProcedures) {
                        var numCell = 27;
                    } else {
                        var numCell = 19;
                    }
                    mainSheet.getCell('A' + numCell).value = "COMPONENT VALIDATED PIVOT";
                    infoComponentMap.forEach((values,keys)=>{
                        numCell++;
                        mainSheet.getCell('A' + numCell).value = keys;
                        mainSheet.getCell('B' + numCell).value = values;
                    });
                }
            }

            var details = deployStatus.details;
            console.log('details:' + details);

            if (details.componentSuccesses) {
                console.log("Adding sheet " + SHEET_VALIDATED_COMPONENTS);

                const sheet = workbook.addWorksheet(SHEET_VALIDATED_COMPONENTS, { views: [{ showGridLines: false }] });
                sheet.columns = [
                    { header: 'COMPONENT TYPE', key: 'componentType', width: 20 },
                    { header: 'FILENAME', key: 'fileName', width: 32 },
                    { header: 'FULLNAME', key: 'fullName', width: 32 },
                    { header: 'OWNERS', key: 'owners', width: 42 },
                    { header: 'VENDORS', key: 'vendors', width: 42 },
                    { header: 'INSERTIONS', key: 'insertions', width: 20 },
                    { header: 'DELETIONS', key: 'deletions', width: 20 },
                    { header: 'CREATION DATE', key: 'creationDate', width: 20 }
                ];

                if (Array.isArray(details.componentSuccesses)) {
                    let i = 2;
                    details.componentSuccesses.forEach(cf => {
                        var owners = "";
                        var vendors = "";
                        let deletions;
                        let insertions;
                        let creationDate = "";
                        if (infoObj) {
                            infoObj.forEach(cd => {
                                if (cd.finalFilename == cf.fileName) {
                                    cd.owners.forEach(c => {
                                        if (cd.owners.length > 1) {
                                            owners += c + ", ";
                                        } else {
                                            owners += c;
                                        }
                                    });
                                    if (owners.endsWith(", ")) {
                                        owners = owners.slice(0, -2);
                                    }
                                    cd.vendors.forEach(v => {
                                        if (cd.vendors.length > 1) {
                                            vendors += v + ", ";
                                        } else {
                                            vendors += v;
                                        }
                                    });
                                    if (vendors.endsWith(", ")) {
                                        vendors = vendors.slice(0, -2);
                                    }
                                    if (cd.deletion >= 0) {
                                        deletions = cd.deletion;
                                    }
                                    if (cd.insertion >= 0) {
                                        insertions = cd.insertion;
                                    }
                                    if (cd.creationDate) {
                                        creationDate = cd.creationDate;
                                    }  
                                }
                            });
                        } else {
                            owners = 'NA';
                            vendors = 'NA';
                            insertions = 'NA';
                            deletions = 'NA';
                            creationDate = 'NA';
                        }
                        const row = sheet.getRow(i);
                        row.getCell(1).value = cf.componentType;
                        row.getCell(2).value = cf.fileName;
                        row.getCell(3).value = cf.fullName;
                        row.getCell(4).value = owners;
                        row.getCell(5).value = vendors;
                        row.getCell(6).value = insertions;
                        row.getCell(7).value = deletions;
                        row.getCell(8).value = creationDate;
                        i++;
                    });
                } else {
                    const row = sheet.getRow(2);
                    var owners = "";
                    var vendors = "";
                    let deletions;
                    let insertions;
                    let creationDate = "";
                    if (infoObj) {
                        infoObj.forEach(cd => {
                            console.log(details.componentSuccesses.fullName);
                            if (cd.finalFilename == details.componentSuccesses.fileName) {
                                cd.owners.forEach(c => {
                                    if (cd.owners.length > 1) {
                                        owners += c + ", ";
                                    } else {
                                        owners += c;
                                    }
                                });
                                if (owners.endsWith(", ")) {
                                    owners = owners.slice(0, -2);
                                }
                                cd.vendors.forEach(v => {
                                    if (cd.vendors.length > 1) {
                                        vendors += v + ", ";
                                    } else {
                                        vendors += v;
                                    }
                                });
                                if (vendors.endsWith(", ")) {
                                    vendors = vendors.slice(0, -2);
                                }
                                if (cd.deletion >= 0) {
                                    deletions = cd.deletion;
                                }
                                if (cd.insertion >= 0) {
                                    insertions = cd.insertion;
                                }
                                if (cd.creationDate) {
                                    creationDate = cd.creationDate;
                                }
                            }
                        });
                    } else {
                        owners = 'NA';
                        vendors = 'NA';
                        insertions = 'NA';
                        deletions = 'NA';
                        creationDate = 'NA';
                    }
                    row.getCell(1).value = details.componentSuccesses.componentType;
                    row.getCell(2).value = details.componentSuccesses.fileName;
                    row.getCell(3).value = details.componentSuccesses.fullName;
                    row.getCell(4).value = owners;
                    row.getCell(5).value = vendors;
                    row.getCell(6).value = insertions;
                    row.getCell(7).value = deletions;
                    row.getCell(8).value = creationDate;
                }

                sheet.eachRow({ includeEmpty: false }, function (row, rowNumber) {
                    row.eachCell(function (cell, colNumber) {
                        if (rowNumber == 1) {
                            cell.font = {
                                bold: true,
                                color: { argb: 'FFFFFF' }
                            };
                            cell.fill = {
                                type: 'pattern',
                                pattern: 'solid',
                                fgColor: { argb: '000000' }
                            };
                        }
                        if (colNumber == 6 || colNumber == 7) {
                            cell.alignment = {
                                horizontal: 'center'
                            };
                        }
                    });
                });
            }

            if (details.componentFailures) {
                console.log("Adding sheet " + SHEET_COMPONENT_ERRORS);

                const sheet = workbook.addWorksheet(SHEET_COMPONENT_ERRORS, { views: [{ showGridLines: false }] });
                sheet.columns = [
                    { header: 'COMPONENT TYPE', key: 'componentType', width: 20 },
                    { header: 'FILENAME', key: 'fileName', width: 32 },
                    { header: 'FULLNAME', key: 'fullName', width: 32 },
                    { header: 'LINE NUMBER', key: 'lineNumber', width: 16 },
                    { header: 'PROBLEM', key: 'problem', width: 64 },
                    { header: 'PROBLEM TYPE', key: 'problemType', width: 32 },
                    { header: 'OWNERS', key: 'owners', width: 42 },
                    { header: 'VENDORS', key: 'vendors', width: 42 },
                    { header: 'INSERTIONS', key: 'insertions', width: 20 },
                    { header: 'DELETIONS', key: 'deletions', width: 20 },
                    { header: 'CREATION DATE', key: 'creationDate', width: 20 }
                ];
                if (Array.isArray(details.componentFailures)) {
                    let i = 2;
                    details.componentFailures.forEach(cf => {
                        var owners = "";
                        var vendors = "";
                        let deletions;
                        let insertions;
                        let creationDate = "";
                        if (infoObj) {
                            infoObj.forEach(cd => {
                                if (cd.finalFilename == cf.fileName) {
                                    cd.owners.forEach(c => {
                                        if (cd.owners.length > 1) {
                                            owners += c + ", ";
                                        } else {
                                            owners += c;
                                        }
                                    });
                                    if (owners.endsWith(", ")) {
                                        owners = owners.slice(0, -2);
                                    }
                                    cd.vendors.forEach(v => {
                                        if (cd.vendors.length > 1) {
                                            vendors += v + ", ";
                                        } else {
                                            vendors += v;
                                        }
                                    });
                                    if (vendors.endsWith(", ")) {
                                        vendors = vendors.slice(0, -2);
                                    }
                                    if (cd.deletion >= 0) {
                                        deletions = cd.deletion;
                                    }
                                    if (cd.insertion >= 0) {
                                        insertions = cd.insertion;
                                    }
                                    if (cd.creationDate) {
                                        creationDate = cd.creationDate;
                                    }
                                }
                            });
                        } else {
                            owners = 'NA';
                            vendors = 'NA';
                            insertions = 'NA';
                            deletions = 'NA';
                            creationDate = 'NA';
                        }

                        const row = sheet.getRow(i);
                        row.getCell(1).value = cf.componentType;
                        row.getCell(2).value = cf.fileName;
                        row.getCell(3).value = cf.fullName;
                        if (cf.lineNumber) {
                            row.getCell(4).value = Number(cf.lineNumber);
                        } else {
                            row.getCell(4).value = 'NA';
                        }
                        row.getCell(5).value = cf.problem;
                        row.getCell(6).value = cf.problemType;
                        row.getCell(7).value = owners;
                        row.getCell(8).value = vendors;
                        row.getCell(9).value = insertions;
                        row.getCell(10).value = deletions;
                        row.getCell(11).value = creationDate;
                        i++;
                    });
                } else {
                    const row = sheet.getRow(2);
                    let owners = "";
                    let vendors = "";
                    let deletions;
                    let insertions;
                    let creationDate = "";
                    if (infoObj) {
                        infoObj.forEach(cd => {
                            if (cd.finalFilename == details.componentFailures.fileName) {
                                cd.owners.forEach(c => {
                                    if (cd.owners.length > 1) {
                                        owners += c + ", ";
                                    } else {
                                        owners += c;
                                    }
                                });
                                if (owners.endsWith(", ")) {
                                    owners = owners.slice(0, -2);
                                }
                                cd.vendors.forEach(v => {
                                    if (cd.vendors.length > 1) {
                                        vendors += v + ", ";

                                    } else {
                                        vendors += v;
                                    }
                                });
                                if (vendors.endsWith(", ")) {
                                    vendors = vendors.slice(0, -2);
                                }
                                if (cd.deletion >= 0) {
                                    deletions = cd.deletion;
                                }
                                if (cd.insertion >= 0) {
                                    insertions = cd.insertion;
                                }
                                if (cd.creationDate) {
                                    creationDate = cd.creationDate;
                                }
                            }
                        });
                    } else {
                        owners = 'NA';
                        vendors = 'NA';
                        insertions = 'NA';
                        deletions = 'NA';
                        creationDate = 'NA';
                    }
                    
                    row.getCell(1).value = details.componentFailures.componentType;
                    row.getCell(2).value = details.componentFailures.fileName;
                    row.getCell(3).value = details.componentFailures.fullName;
                    if (details.componentFailures.lineNumber) {
                        row.getCell(4).value = Number(details.componentFailures.lineNumber);
                    } else {
                        row.getCell(4).value = 'NA';
                    }
                    row.getCell(5).value = details.componentFailures.problem;
                    row.getCell(6).value = details.componentFailures.problemType;
                    row.getCell(7).value = owners;
                    row.getCell(8).value = vendors;
                    row.getCell(9).value = insertions;
                    row.getCell(10).value = deletions;
                    row.getCell(11).value = creationDate;
                }

                sheet.eachRow({ includeEmpty: false }, function (row, rowNumber) {
                    row.eachCell(function (cell, colNumber) {
                        if (rowNumber == 1) {
                            cell.font = {
                                bold: true,
                                color: { argb: 'FFFFFF' }
                            };
                            cell.fill = {
                                type: 'pattern',
                                pattern: 'solid',
                                fgColor: { argb: '000000' }
                            };
                        }
                        if (colNumber == 4 || colNumber == 9 || colNumber == 10) {
                            cell.alignment = {
                                horizontal: 'center'
                            };
                        }
                    });
                });
            }

            if (details.runTestResult && details.runTestResult.failures) {
                console.log("Adding sheet " + SHEET_TEST_FAILURES);

                const sheet = workbook.addWorksheet(SHEET_TEST_FAILURES, { views: [{ showGridLines: false }] });
                sheet.columns = [
                    { header: 'CLASS NAME', key: 'className', width: 32 },
                    { header: 'METHOD NAME', key: 'methodName', width: 32 },
                    { header: 'MESSAGE', key: 'message', width: 64 },
                    { header: 'STACKTRACE', key: 'stackTrace', width: 64 }
                ];

                if (Array.isArray(details.runTestResult.failures)) {
                    let i = 2;
                    details.runTestResult.failures.forEach(cc => {
                        const row = sheet.getRow(i);
                        row.getCell(1).value = cc.name;
                        row.getCell(2).value = cc.methodName;
                        row.getCell(3).value = cc.message;
                        row.getCell(4).value = cc.stackTrace;
                        i++;
                    });
                } else {
                    const row = sheet.getRow(2);
                    row.getCell(1).value = details.runTestResult.failures.name;
                    row.getCell(2).value = details.runTestResult.failures.methodName;
                    row.getCell(3).value = details.runTestResult.failures.message;
                    row.getCell(4).value = details.runTestResult.failures.stackTrace;
                }

                sheet.eachRow({ includeEmpty: false }, function (row, rowNumber) {
                    row.eachCell(function (cell, colNumber) {
                        if (rowNumber == 1) {
                            cell.font = {
                                bold: true,
                                color: { argb: 'FFFFFF' }
                            };
                            cell.fill = {
                                type: 'pattern',
                                pattern: 'solid',
                                fgColor: { argb: '000000' }
                            };
                        }
                    });
                });
            }

            if (details.runTestResult && (details.runTestResult.codeCoverage || details.runTestResult.codeCoverageWarnings)) {
                console.log("Adding sheet " + SHEET_TEST_EXECUTION);

                const sheet = workbook.addWorksheet(SHEET_TEST_EXECUTION, { views: [{ showGridLines: false }] });
                sheet.columns = [
                    { header: 'CLASS NAME', key: 'name', width: 32 },
                    { header: 'TOTAL ROWNUMS', key: 'numLocations', width: 20 },
                    { header: 'NOT COVERED ROWNUMS', key: 'numLocationsNotCovered', width: 20 },
                    { header: 'COVERAGE %', key: 'coverage', width: 20 },
                    { header: 'LOCATIONS NOT COVERED', key: 'coverage', width: 150 }
                ];

                let i = 2;
                if (details.runTestResult.codeCoverage) {
                    if (Array.isArray(details.runTestResult.codeCoverage)) {
                        details.runTestResult.codeCoverage.forEach(cc => {
                            const row = sheet.getRow(i);
                            row.getCell(1).value = cc.name;
                            row.getCell(2).value = Number(cc.numLocations);
                            row.getCell(3).value = Number(cc.numLocationsNotCovered);
                            if (cc.numLocations > 0) {
                                row.getCell(4).value = (1 - (cc.numLocationsNotCovered / cc.numLocations));
                            } else {
                                row.getCell(4).value = 0;
                            }

                            var notCoveredLines = '';
                            if (cc.locationsNotCovered != null) {
                                if (Array.isArray(cc.locationsNotCovered)) {
                                    cc.locationsNotCovered.forEach(loc => {
                                        notCoveredLines += loc.line + ';';
                                    });
                                    notCoveredLines = notCoveredLines.slice(0, -1);
                                } else {
                                    notCoveredLines = cc.locationsNotCovered.line;
                                }
                            }
                            row.getCell(5).value = notCoveredLines;
                            i++;
                        });
                    } else {
                        const row = sheet.getRow(2);
                        row.getCell(1).value = details.runTestResult.codeCoverage.name;
                        row.getCell(2).value = Number(details.runTestResult.codeCoverage.numLocations);
                        row.getCell(3).value = Number(details.runTestResult.codeCoverage.numLocationsNotCovered);
                        if (details.runTestResult.codeCoverage.numLocations > 0) {
                            row.getCell(4).value = (1 - (details.runTestResult.codeCoverage.numLocationsNotCovered / details.runTestResult.codeCoverage.numLocations));
                        } else {
                            row.getCell(4).value = 0;
                        }

                        var notCoveredLines = '';
                        if (details.runTestResult.codeCoverage.locationsNotCovered != null) {
                            if (Array.isArray(details.runTestResult.codeCoverage.locationsNotCovered)) {
                                details.runTestResult.codeCoverage.locationsNotCovered.forEach(loc => {
                                    notCoveredLines += loc.line + ';';
                                });
                                notCoveredLines = notCoveredLines.slice(0, -1);
                            } else {
                                notCoveredLines = details.runTestResult.codeCoverage.locationsNotCovered.line;
                            }
                        }
                        row.getCell(5).value = notCoveredLines;
                        i++;
                    }
                }

                if (details.runTestResult.codeCoverageWarnings) {
                    if (Array.isArray(details.runTestResult.codeCoverageWarnings)) {
                        details.runTestResult.codeCoverageWarnings.forEach(cc => {
                            if (cc.message.indexOf(' 0%') > -1) {
                                const row = sheet.getRow(i);
                                row.getCell(1).value = cc.name;
                                row.getCell(4).value = 0;
                                i++;
                            }
                        });
                    } else {
                        if (details.runTestResult.codeCoverageWarnings.message.indexOf(' 0%') > -1) {
                            const row = sheet.getRow(i);
                            row.getCell(1).value = details.runTestResult.codeCoverageWarnings.name;
                            row.getCell(4).value = 0;
                        }
                    }
                }

                sheet.eachRow({ includeEmpty: false }, function (row, rowNumber) {
                    row.eachCell(function (cell, colNumber) {
                        if (rowNumber == 1) {
                            cell.font = {
                                bold: true,
                                color: { argb: 'FFFFFF' }
                            };
                            cell.fill = {
                                type: 'pattern',
                                pattern: 'solid',
                                fgColor: { argb: '000000' }
                            };
                        }
                        if (colNumber != 1) {
                            cell.alignment = {
                                horizontal: 'center'
                            };
                        }
                        if (colNumber == 4) {
                            cell.numFmt = '0.00%';
                        }
                    });
                });
            }

            if (reportPMD !== undefined) {
                var detailsPMD = PMDobj.pmdVersion;

                if (detailsPMD) {
                    console.log("Adding sheet " + SHEET_PMD_REPORT);

                    const sheet = workbook.addWorksheet(SHEET_PMD_REPORT, { views: [{ showGridLines: false }] });
                    sheet.columns = [
                        { header: 'FILE NAME', key: 'fileName', width: 40 },
                        { header: 'BEGIN LINE', key: 'beginline', width: 15 },
                        { header: 'BEGIN COLUMN', key: 'begincolumn', width: 15 },
                        { header: 'END LINE', key: 'endline', width: 15 },
                        { header: 'END COLUMN', key: 'endcolumn', width: 15 },
                        { header: 'DESCRIPTION', key: 'description', width: 60 },
                        { header: 'RULE', key: 'rule', width: 32 },
                        { header: 'RULE SET', key: 'ruleset', width: 32 },
                        { header: 'PRIORITY', key: 'priority', width: 10 },
                        { header: 'EXTERNAL URL', key: 'externalInfoUrl', width: 80 }
                    ];
                    let i = 2;
                    PMDobj.files.forEach(cf => {
                        cf.violations.forEach(vio_i => {
                            const row = sheet.getRow(i);
                            row.getCell(1).value = (cf.filename);
                            row.getCell(2).value = (vio_i.beginline);
                            row.getCell(3).value = (vio_i.begincolumn);
                            row.getCell(4).value = (vio_i.endline);
                            row.getCell(5).value = (vio_i.endcolumn);
                            row.getCell(6).value = (vio_i.description);
                            row.getCell(7).value = (vio_i.rule);
                            row.getCell(8).value = (vio_i.ruleset);
                            row.getCell(9).value = (vio_i.priority);
                            row.getCell(10).value = (vio_i.externalInfoUrl);
                            i++;
                        });
                    });

                    sheet.eachRow({ includeEmpty: false }, function (row, rowNumber) {
                        row.eachCell(function (cell, colNumber) {
                            if (rowNumber == 1) {
                                cell.font = {
                                    bold: true,
                                    color: { argb: 'FFFFFF' }
                                };
                                cell.fill = {
                                    type: 'pattern',
                                    pattern: 'solid',
                                    fgColor: { argb: '000000' }
                                };
                            }
                            if (colNumber == 2 || colNumber == 3 || colNumber == 4 || colNumber == 5 || colNumber == 9) {
                                cell.alignment = {
                                    horizontal: 'center'
                                };
                            }
                        });
                    });
                }
            }

            deferred.resolve();
        } else {
            deferred.reject(new Error('deployStatus or deployStatus.details are null'));
        }

    }

    if (manualObj && manualObj.vendors && (manualObj.vendors.length > 0)) {
        console.log("Adding sheet " + SHEET_MANUAL_PROCEDURES);
        var mpCount = 0;

        manualObj.vendors.forEach(vendor => {
            mpCount += vendor.manualProcedures.length;
        });

        if (mpCount > 0) {
            const sheet = workbook.addWorksheet('Manual Procedures', { views: [{ showGridLines: false }] });
            var toBeExecColId = 0;
            const row = sheet.getRow(1);
            row.getCell(1).value = "VENDOR NAME";
            sheet.getColumn(1).width = row.getCell(1).value.length + 10;
            manualObj.vendors.forEach(vendor => {
                var j = 2;
                var manual = vendor.manualProcedures[0];
                if (manual) {
                    for (var col in manual) {
                        var widthCol = sheet.getColumn(j);
                        widthCol.width = col.length + 10;
                        row.getCell(j).value = col;
                        if (col == "TO_BE_EXECUTED") {
                            toBeExecColId = j;
                        }
                        j++;
                    }
                }
            });

            let i = 2;
            manualObj.vendors.forEach(vendor => {
                vendor.manualProcedures.forEach(manual => {
                    const row = sheet.getRow(i);
                    row.getCell(1).value = vendor.name;
                    var j = 2;
                    for (var k in manual) {
                        row.getCell(j).value = manual[k];
                        j++;
                    }
                    i++;
                });
            });

            sheet.eachRow({ includeEmpty: false }, function (row, rowNumber) {
                if (row.getCell(toBeExecColId)._value.model.value) {
                    var i = 1;
                    for (i; i < toBeExecColId + 1; i++) {
                        row.getCell(i).fill = {
                            type: 'pattern',
                            pattern: 'solid',
                            fgColor: { argb: 'FFFF00' }
                        };
                    }
                }

                row.eachCell(function (cell, colNumber) {
                    if (rowNumber == 1) {
                        cell.font = {
                            bold: true,
                            color: { argb: 'FFFFFF' }
                        };
                        cell.fill = {
                            type: 'pattern',
                            pattern: 'solid',
                            fgColor: { argb: '000000' }
                        };
                    }
                    if (colNumber != 1) {
                        cell.alignment = {
                            horizontal: 'center'
                        };
                    }
                });
            });
        }
    }

    if (reportVlocityComponents !== undefined) {
        var detailsVlocityComponents = vlocityObj;

        if (detailsVlocityComponents) {
            console.log("Adding sheet " + SHEET_VLOCITY_CATALOG);

            const sheet = workbook.addWorksheet(SHEET_VLOCITY_CATALOG, { views: [{ showGridLines: false }] });
            sheet.columns = [
                { header: 'COMPONENT TYPE', key: 'componentType', width: 40 },
                { header: 'COMPONENT NAME', key: 'componentName', width: 40 }
            ];

            let i = 2;
            var keys = Object.keys(detailsVlocityComponents);    
            for (const k of keys) {
                for (const comp of detailsVlocityComponents[k]) {
                    const row = sheet.getRow(i);
                    row.getCell(1).value = k;
                    row.getCell(2).value = comp;
                    i++;   
                };   
            };

            sheet.eachRow({ includeEmpty: false }, function (row, rowNumber) {
                row.eachCell(function (cell, colNumber) {
                    if (rowNumber == 1) {
                        cell.font = {
                            bold: true,
                            color: { argb: 'FFFFFF' }
                        };
                        cell.fill = {
                            type: 'pattern',
                            pattern: 'solid',
                            fgColor: { argb: '000000' }
                        };
                    }
                });
            });
        }
    }

    workbook.xlsx.writeFile(reportFile);

    return deferred.promise;
};

async function toCamelCase(str) {
    var result = "";
    str = str.replace(/_/g, ' ');
    str = str.replace(/#/g, ' ');
    str = str.replace(/:/g, '');
    str = str.trim();

    var resultChunk = str.split(' ');
    for (i = 0; i < resultChunk.length; i++) {
        var s = resultChunk[i].toLowerCase();
        if (i > 0) {
            s = resultChunk[i].substr(0, 1).toUpperCase() + resultChunk[i].substr(1).toLowerCase();
        }
        result += s;
    }

    return result;
}

var getSheetJson = async function (sheet, sheetName) {
    var headers = [];
    var lines = [];

    sheet.eachRow({ includeEmpty: false }, function (row, rowNumber) {
        if (rowNumber == 1) {
            row.eachCell(function (cell, colNumber) {
                headers.push(cell.value);
            });
        } else {
            var lineObj = {};
            row.eachCell(function (cell, colNumber) {
                var element_header = headers[colNumber - 1];
                var add_element = true;
                if (sheetName == SHEET_PMD_REPORT && element_header == "EXTERNAL URL") {
                    add_element = false;
                }
                if (element_header == 'LINE NUMBER' && cell.value == NOT_AVAILABLE_STR) {
                    add_element = false;
                }
                if (add_element) {
                    var element_key = toCamelCase(element_header);
                    lineObj[element_key] = cell.value;
                }
            });
            lines.push(lineObj);
        }
    });

    return lines;
};

var addSheetJsonByName = async function (workbook, sheetName, monitoringJson) {
    var sheet = workbook.getWorksheet(sheetName);
    console.log('### addSheetJsonByName: ' + sheetName + " - " + sheet);
    if (sheet != null) {
        var sheetJson = getSheetJson(sheet, sheetName);
        var element_key = toCamelCase(sheetName);
        monitoringJson[element_key] = sheetJson;
    }
};

var getRecapSheetJson = async function (sheet) {
    var headers = [];
    var lines = [];
    var lineObj = {};

    sheet.eachRow({ includeEmpty: false }, function (row, rowNumber) {
        var header = null;
        var headerCol = null;
        var value = null;
        var valueCol = null;

        row.eachCell(function (cell, colNumber) {
            if (colNumber % 2 == 1) {
                header = cell.value;
                headerCol = colNumber;
            } else {
                value = cell.value;
                valueCol = colNumber;
            }

            if (header != null && value != null && value != NOT_AVAILABLE_STR && (headerCol == valueCol - 1)) {
                element_key = toCamelCase(header);
                lineObj[element_key] = value;
            }
        });
    });
    lines.push(lineObj);

    return lineObj;
};

var addSummaryJson = async function (workbook, monitoringJson) {
    var sheetName = SHEET_RECAP;
    var sheet = workbook.getWorksheet(sheetName);
    console.log('### addSummaryJson: ' + sheetName + " - " + sheet);
    if (sheet != null) {
        var sheetJson = getRecapSheetJson(sheet);
        var element_key = toCamelCase(sheetName);
        monitoringJson[element_key] = sheetJson;
    }
};

var convertToJson = async function () {
    console.log('### convertToJson started ###');

    if (workbook != null) {
        console.log('workbook ok');
    } else {
        console.error('workbook error');
    }

    try {
        var monitoringJson = {};

        addSummaryJson(workbook, monitoringJson);
        // not for SHEET_RECAP
        addSheetJsonByName(workbook, SHEET_VALIDATED_COMPONENTS, monitoringJson);
        addSheetJsonByName(workbook, SHEET_COMPONENT_ERRORS, monitoringJson);
        addSheetJsonByName(workbook, SHEET_TEST_FAILURES, monitoringJson);
        addSheetJsonByName(workbook, SHEET_TEST_EXECUTION, monitoringJson);
        addSheetJsonByName(workbook, SHEET_PMD_REPORT, monitoringJson);
        addSheetJsonByName(workbook, SHEET_MANUAL_PROCEDURES, monitoringJson);

        try {
            fs.writeFileSync(MONITORING_JSON_FILENAME, JSON.stringify(monitoringJson));
        } catch (err) {
            console.error(MONITORING_JSON_FILENAME + ' write error: ' + err);
        }
    } catch (err) {
        console.error(err);
    }
};

queueSteps();

function queueSteps() {
    Q.fcall(sfdcLogin)
        .then(sfdcCheckDeployStatus())
        .then(readInputsJson())
        .then(readManualJson())
        .then(readInfoCommitJson())
        .then(readVlocityComponetsJson())
        .then(readPMDJson())
        .then(createXlsFile())
        .then(convertToJson())
        .catch(function (error) {
            'use strict';
            console.error(error);
            process.exit(1);
        })
        .done(function () {
            'use strict';
            sfdcLogout();
        });
}