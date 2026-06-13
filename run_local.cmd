@echo off
setlocal

set PORT=%1
if "%PORT%"=="" set PORT=8081

echo Starting Spring eShopOnContainers MVP on http://localhost:%PORT%
echo.

mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=%PORT%

