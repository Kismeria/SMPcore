@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem ============================================================
rem  Сборка SmpCore. Положи этот файл в папку проекта (рядом с build.gradle.kts)
rem  и запусти двойным кликом.
rem ============================================================

rem Папка plugins сервера: туда скопируется готовый jar. Пусто — не копировать.
rem Пример: set "SERVER_PLUGINS=C:\Server\plugins"
set "SERVER_PLUGINS="

rem 1 — перед сборкой подтянуть свежий код с GitHub (git pull), 0 — собрать то, что есть
set "PULL=1"

if "%PULL%"=="1" (
    where git >nul 2>nul
    if errorlevel 1 (
        echo [!] git не найден в PATH — пропускаю обновление с GitHub
    ) else if exist ".git" (
        echo === Обновляю код с GitHub ===
        git pull
        if errorlevel 1 (
            echo [X] git pull не удался — сохрани или откати свои изменения и запусти снова
            goto :fail
        )
    ) else (
        echo [!] Это не git-папка — пропускаю обновление с GitHub
    )
)

if not exist "run\host\paper-26.3.jar" (
    echo [!] Нет run\host\paper-26.3.jar — без него не соберутся позы /lay и /crawl
)

echo.
echo === Собираю плагин ===
if exist "gradlew.bat" (
    call gradlew.bat clean build
) else (
    call gradle clean build
)
if errorlevel 1 goto :fail

set "JAR="
for %%F in ("build\libs\SmpCore-*.jar") do set "JAR=%%~fF"
if not defined JAR (
    echo [X] Сборка прошла, но jar в build\libs не найден
    goto :fail
)

echo.
echo [OK] Готово: %JAR%

if defined SERVER_PLUGINS (
    if not exist "%SERVER_PLUGINS%" (
        echo [!] Папка %SERVER_PLUGINS% не найдена — не копирую
    ) else (
        del /q "%SERVER_PLUGINS%\SmpCore-*.jar" 2>nul
        copy /y "%JAR%" "%SERVER_PLUGINS%\" >nul
        echo [OK] Скопировано в %SERVER_PLUGINS%
    )
)

explorer /select,"%JAR%"
echo.
pause
exit /b 0

:fail
echo.
echo [X] Сборка не удалась — смотри ошибки выше
pause
exit /b 1
