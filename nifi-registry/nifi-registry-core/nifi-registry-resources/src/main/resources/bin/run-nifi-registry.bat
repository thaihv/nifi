@echo off
rem
rem    Licensed to the Apache Software Foundation (ASF) under one or more
rem    contributor license agreements.  See the NOTICE file distributed with
rem    this work for additional information regarding copyright ownership.
rem    The ASF licenses this file to You under the Apache License, Version 2.0
rem    (the "License"); you may not use this file except in compliance with
rem    the License.  You may obtain a copy of the License at
rem
rem       http://www.apache.org/licenses/LICENSE-2.0
rem
rem    Unless required by applicable law or agreed to in writing, software
rem    distributed under the License is distributed on an "AS IS" BASIS,
rem    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
rem    See the License for the specific language governing permissions and
rem    limitations under the License.
rem


call %~sdp0\nifi-registry-env.bat

rem Use JAVA_HOME if it's set; otherwise, just use java

if "%JAVA_HOME%" == "" goto noJavaHome
if not exist "%JAVA_HOME%\bin\java.exe" goto noJavaHome
set JAVA_EXE=%JAVA_HOME%\bin\java.exe
goto startNiFiRegistry

:noJavaHome
echo The JAVA_HOME environment variable is not defined correctly.
echo Instead the PATH will be used to find the java executable.
echo.
set JAVA_EXE=java
goto startNiFiRegistry

:startNiFiRegistry
pushd "%NIFI_REGISTRY_ROOT%\"
set LIB_DIR=%NIFI_REGISTRY_ROOT%\lib
set SHARED_DIR=%NIFI_REGISTRY_ROOT%\lib\shared
set BOOTSTRAP_DIR=%NIFI_REGISTRY_ROOT%\lib\bootstrap
set CONF_DIR=%NIFI_REGISTRY_ROOT%\conf

set BOOTSTRAP_CONF_FILE=%CONF_DIR%\bootstrap.conf
set JAVA_ARGS=-Dorg.apache.nifi.registry.bootstrap.config.log.dir=%NIFI_REGISTRY_LOG_DIR% -Dorg.apache.nifi.registry.bootstrap.config.file=%BOOTSTRAP_CONF_FILE%

SET JAVA_PARAMS=-cp %CONF_DIR%;%LIB_DIR%\*;%SHARED_DIR%\*;%BOOTSTRAP_DIR%\* -Xms512m -Xmx1024m %JAVA_ARGS% org.apache.nifi.registry.NiFiRegistry
set BOOTSTRAP_ACTION=run

echo cmd.exe /C "%JAVA_EXE%" %JAVA_PARAMS% %BOOTSTRAP_ACTION%
cmd.exe /C "%JAVA_EXE%" %JAVA_PARAMS% %BOOTSTRAP_ACTION%

popd
