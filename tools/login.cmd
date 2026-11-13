@echo off
setlocal
REM ---------------------------------------------------------------------------
REM login.cmd -- auto login to Guosen (double-click me, or run from cmd)
REM Credentials: tools\login.local.env  (gitignored; template: tools\login.example.env)
REM Needs git-bash. This file is intentionally ASCII-only (cmd codepage safety).
REM ---------------------------------------------------------------------------
set "HERE=%~dp0"
set "BASH="
where bash >nul 2>&1 && set "BASH=bash"
if not defined BASH if exist "%ProgramFiles%\Git\bin\bash.exe" set "BASH=%ProgramFiles%\Git\bin\bash.exe"
if not defined BASH if exist "%ProgramFiles(x86)%\Git\bin\bash.exe" set "BASH=%ProgramFiles(x86)%\Git\bin\bash.exe"
if not defined BASH if exist "%LOCALAPPDATA%\Programs\Git\bin\bash.exe" set "BASH=%LOCALAPPDATA%\Programs\Git\bin\bash.exe"
if not defined BASH (
  echo [login] git-bash not found. Install Git for Windows first.
  pause
  exit /b 2
)
"%BASH%" "%HERE%login.sh" %*
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" pause
exit /b %RC%
