FROM ubuntu:18.04
USER root

# OpenJDK version
ENV OPENJDK_VERSION 11

# Apache Ant
ENV ANT_VERSION 1.10.12
ENV ANT_HOME /opt/ant

# Salesforce Ant migration tool
ENV SF_ANT_VERSION 52.0

# Other tools version
ENV NVM_VERSION 0.39.1
ENV NODE_VERSION 14.19.3
ENV SFDX_VERSION 7.154.0
ENV PMD_VERSION 6.30.0
ENV VBT_VERSION 1.15.5

# install utility tools
RUN apt-get update \
  && apt-get install wget unzip curl xz-utils git openjdk-${OPENJDK_VERSION}-jdk -y \
  && apt-get autoremove --assume-yes \
  && apt-get clean --assume-yes \
  && rm -rf /var/lib/apt/lists/*

 
# install ANT
RUN cd /tmp \
  && wget https://downloads.apache.org/ant/binaries/apache-ant-${ANT_VERSION}-bin.tar.gz -q \
  && mkdir ant-${ANT_VERSION} \
  && tar -zxvf apache-ant-${ANT_VERSION}-bin.tar.gz \
  && mv apache-ant-${ANT_VERSION} ${ANT_HOME} \
  && rm apache-ant-${ANT_VERSION}-bin.tar.gz \
  && rm -rf ant-${ANT_VERSION} \
  && rm -rf ${ANT_HOME}/manual \
  && unset ANT_VERSION

# install ANT Migration Tool
RUN cd /tmp \
  && mkdir salesforce-ant-${SF_ANT_VERSION} \
  && wget https://gs0.salesforce.com/dwnld/SfdcAnt/salesforce_ant_${SF_ANT_VERSION}.zip -q \
  && unzip -d salesforce-ant-${SF_ANT_VERSION} salesforce_ant_${SF_ANT_VERSION}.zip \
  && mv salesforce-ant-${SF_ANT_VERSION}/ant-salesforce.jar ${ANT_HOME}/lib \
  && rm salesforce_ant_${SF_ANT_VERSION}.zip \
  && rm -rf salesforce-ant-${SF_ANT_VERSION} \
  && unset SF_ANT_VERSION

# install nvm, nodejs, sfdx and sfpowerkit plugin
RUN mkdir -p /usr/local/nvm
ENV NVM_DIR=/usr/local/nvm

RUN curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v${NVM_VERSION}/install.sh | bash

RUN . $NVM_DIR/nvm.sh \
  && nvm install ${NODE_VERSION} \
  && nvm alias default v${NODE_VERSION} \
  && nvm use default \
  && npm install --global sfdx-cli@${SFDX_VERSION} \
  && echo 'y' | sfdx plugins:install sfpowerkit

ENV NODE_PATH $NVM_DIR/v$NODE_VERSION/lib/node_modules
ENV PATH $NVM_DIR/versions/node/v$NODE_VERSION/bin:$ANT_HOME/bin:$PATH

# GIT configs
RUN git config --global user.email 'Jenkins CI' \
  && git config --global user.name 'jenkins@example.com'

# install PMD
RUN cd /tmp \
  && wget https://github.com/pmd/pmd/releases/download/pmd_releases%2F${PMD_VERSION}/pmd-bin-${PMD_VERSION}.zip -q \
  && unzip -d pmd pmd-bin-${PMD_VERSION}.zip \
  && mv pmd /opt/pmd \
  && mv /opt/pmd/pmd-bin-${PMD_VERSION} /opt/pmd/pmd-bin \
  && echo 'alias pmd="/opt/pmd/pmd-bin/bin/run.sh pmd"' >> ~/.bashrc \
  && rm -rf pmd-bin-${PMD_VERSION}.zip
# pmd rules 
# && git clone https://github.com/pmd/pmd.git  substituition row 78 with row 80 (18/10/22)
RUN cd /tmp \ 
  && git clone --depth 1 --branch pmd_releases/${PMD_VERSION} https://github.com/pmd/pmd.git \
  && cp -r pmd/pmd-apex/src/main/resources/rulesets/apex/ /opt/pmd/apexrules \
  && rm -rf pmd

# vlocity
RUN npm install --global vlocity@${VBT_VERSION}

# COPY FOLDER sfdc-report-cli
COPY ./sfdc-report-cli /tmp/sfdc-report-cli
RUN cd /tmp/sfdc-report-cli \
  && npm install -g
