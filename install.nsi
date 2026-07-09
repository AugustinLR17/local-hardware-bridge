; Local Hardware Bridge - NSIS Installer
; ==========================================
; Single self-contained .exe installer.
;
; It packages the jpackage *app-image* produced by the build:
;   - a bundled Java runtime (no JRE required on the target machine)
;   - a windowless native launcher "Local Hardware Bridge.exe"
;     (GUI subsystem -> no console/terminal window)
;
; The installer wires up Start Menu / Desktop shortcuts and registers
; auto-start via HKCU\...\\Run pointing at that windowless launcher.
;
; Install scope:
;   Default (per-user):  makensis /DPRODUCT_VERSION=<ver> install.nsi
;   Per-machine:         makensis /DPRODUCT_VERSION=<ver> /DPER_MACHINE=1 install.nsi
;
; Build:
;   1. ./gradlew shadowJar
;   2. jpackage --type app-image --name "Local Hardware Bridge" \
;        --input build/libs --main-jar local-hardware-bridge-<ver>.jar \
;        --main-class io.github.augustinlr17.localhardwarebridge.Launcher \
;        --dest build/dist/appimage --icon icon.ico
;   3. makensis /DPRODUCT_VERSION=<ver> install.nsi   ->  lhb.exe

; --------------------------------
; Includes
; --------------------------------
!include MUI2.nsh
!include LogicLib.nsh

; --------------------------------
; Defines
; --------------------------------
!define PRODUCT_NAME "Local Hardware Bridge"
!ifndef PRODUCT_VERSION
  !define PRODUCT_VERSION "2.4.0"
!endif
; NUMERIC_VERSION is used for VIProductVersion which requires X.X.X.X format.
; The CI passes it via /DNUMERIC_VERSION=2.4.0.1001; fallback strips the
; pre-release suffix and appends .0 (e.g. 2.4.0-alpha.1 → 2.4.0.0).
!ifndef NUMERIC_VERSION
  !define NUMERIC_VERSION "${PRODUCT_VERSION}"
!endif
!define PRODUCT_PUBLISHER "AugustinLR17"
!define PRODUCT_URL "https://github.com/AugustinLR17/local-hardware-bridge"
!define PRODUCT_UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\Local Hardware Bridge"
!define PRODUCT_REGKEY "SOFTWARE\${PRODUCT_NAME}"
!define RUN_KEY "Software\Microsoft\Windows\CurrentVersion\Run"

; Directory holding the jpackage app-image (relative to this .nsi)
!ifndef APPIMAGE_DIR
  !define APPIMAGE_DIR "build\dist\appimage\${PRODUCT_NAME}"
!endif

; The windowless native launcher created by jpackage (named after --name)
!define LAUNCHER_EXE "${PRODUCT_NAME}.exe"

; --------------------------------
; Sign the uninstaller at compile time (CI only, needs NSIS >= 3.08)
; --------------------------------
; makensis builds the uninstaller EXE stub at compile time and embeds it in the
; installer; WriteUninstaller later extracts that stub verbatim, so a signature
; applied here survives to the client machine. Using !uninstfinalize also makes
; NSIS emit a SEPARATE uninstaller stub, which avoids the corrupt-header side
; effect of signing the installer without it.
;
; Enabled with /DSIGN_UNINSTALLER=1 (CI passes it when ESIGNER_ENABLED). jsign.jar
; and the ES_* environment variables must be available to makensis. "= 0" fails
; the build if signing fails — we never ship an unsigned uninstaller when signing
; is on. Mirrors the jsign/eSigner invocation used for the installer itself.
!ifdef SIGN_UNINSTALLER
  !uninstfinalize 'java -jar jsign.jar --storetype ESIGNER --storepass "$%ES_USER%|$%ES_PASS%" --alias "$%ES_CRED%" --keypass "$%ES_TOTP%" "%1"' = 0
!endif

; --------------------------------
; Install scope: per-user (default) or per-machine (/DPER_MACHINE=1)
; Optional: /DNO_DESKTOP_ICON=1 to uncheck desktop shortcut by default
; --------------------------------
!ifdef PER_MACHINE
  !define REG_ROOT HKLM
  InstallDir "$PROGRAMFILES\${PRODUCT_NAME}"
  RequestExecutionLevel admin
!else
  !define REG_ROOT HKCU
  InstallDir "$LOCALAPPDATA\${PRODUCT_NAME}"
  RequestExecutionLevel user
!endif

; --------------------------------
; Installer attributes
; --------------------------------
Name "${PRODUCT_NAME}"
OutFile "lhb.exe"
InstallDirRegKey ${REG_ROOT} "${PRODUCT_REGKEY}" "Install_Dir"

; Version info embedded in the EXE
VIProductVersion "${NUMERIC_VERSION}"
VIAddVersionKey "ProductName" "${PRODUCT_NAME}"
VIAddVersionKey "CompanyName" "${PRODUCT_PUBLISHER}"
VIAddVersionKey "FileDescription" "${PRODUCT_NAME} Installer"
VIAddVersionKey "FileVersion" "${PRODUCT_VERSION}"
VIAddVersionKey "ProductVersion" "${PRODUCT_VERSION}"
VIAddVersionKey "LegalCopyright" "Copyright (C) 2024-2026 ${PRODUCT_PUBLISHER}"
VIAddVersionKey "OriginalFilename" "lhb.exe"

; --------------------------------
; Modern UI Configuration
; --------------------------------
!define MUI_ICON "icon.ico"
!define MUI_UNICON "icon.ico"
!define MUI_ABORTWARNING

; License page
!define MUI_LICENSEPAGE_CHECKBOX
!insertmacro MUI_PAGE_LICENSE "LICENSE"

; Components page (optional features)
!insertmacro MUI_PAGE_COMPONENTS

; Directory selection
!insertmacro MUI_PAGE_DIRECTORY

; Install progress
!insertmacro MUI_PAGE_INSTFILES

; Finish page - offer to launch app
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "Launch ${PRODUCT_NAME}"
!define MUI_FINISHPAGE_RUN_FUNCTION "LaunchApp"
!insertmacro MUI_PAGE_FINISH

; Uninstaller pages
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

; Language
!insertmacro MUI_LANGUAGE "English"

; --------------------------------
; Section: Main Application (required)
; --------------------------------
Section "!Main Application" SEC_MAIN
  SectionIn RO

  ; Stop a running instance so files can be overwritten
  ExecWait 'taskkill /F /IM "${LAUNCHER_EXE}"' $0

  SetOutPath $INSTDIR

  ; Remove previous payload (old layouts: bundled jre/, loose jars)
  RMDir /r "$INSTDIR\runtime"
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\jre"
  Delete "$INSTDIR\*.jar"

  ; Remove old shortcuts (from previous installs)
  Delete "$DESKTOP\${PRODUCT_NAME}.lnk"
  Delete "$DESKTOP\${PRODUCT_NAME} (CLI).lnk"
  Delete "$DESKTOP\${PRODUCT_NAME} (GUI).lnk"
  Delete "$SMPROGRAMS\${PRODUCT_NAME}.lnk"
  Delete "$SMPROGRAMS\${PRODUCT_NAME} (Server CLI).lnk"
  Delete "$SMSTARTUP\${PRODUCT_NAME}.lnk"

  ; Remove the obsolete VBS auto-start launcher from prior LHB versions.
  ; Auto-start now points directly at the signed exe (see SEC_AUTOSTART),
  ; so the wscript/VBS wrapper is no longer created or used.
  Delete "$INSTDIR\lhb-launcher.vbs"

  ; NOTE: the previous fork "WebApp Hardware Bridge" (WHB) is intentionally
  ; left untouched — LHB no longer removes its shortcuts or registry keys.

  ; Install the jpackage app-image (bundled JRE + windowless launcher) and icon
  File /r "${APPIMAGE_DIR}\*"
  File "icon.ico"

  ; Write registry
  WriteRegStr ${REG_ROOT} "${PRODUCT_REGKEY}" "Install_Dir" "$INSTDIR"
  WriteRegStr ${REG_ROOT} "${PRODUCT_REGKEY}" "Version" "${PRODUCT_VERSION}"

  WriteRegStr ${REG_ROOT} "${PRODUCT_UNINST_KEY}" "DisplayName" "${PRODUCT_NAME}"
  WriteRegStr ${REG_ROOT} "${PRODUCT_UNINST_KEY}" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegStr ${REG_ROOT} "${PRODUCT_UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr ${REG_ROOT} "${PRODUCT_UNINST_KEY}" "DisplayVersion" "${PRODUCT_VERSION}"
  WriteRegStr ${REG_ROOT} "${PRODUCT_UNINST_KEY}" "Publisher" "${PRODUCT_PUBLISHER}"
  WriteRegStr ${REG_ROOT} "${PRODUCT_UNINST_KEY}" "URLInfoAbout" "${PRODUCT_URL}"
  WriteRegStr ${REG_ROOT} "${PRODUCT_UNINST_KEY}" "DisplayIcon" '"$INSTDIR\icon.ico"'
  WriteRegDWORD ${REG_ROOT} "${PRODUCT_UNINST_KEY}" "NoModify" 1
  WriteRegDWORD ${REG_ROOT} "${PRODUCT_UNINST_KEY}" "NoRepair" 1
  WriteRegDWORD ${REG_ROOT} "${PRODUCT_UNINST_KEY}" "EstimatedSize" 150000

  WriteUninstaller "$INSTDIR\uninstall.exe"

  SetAutoClose true
SectionEnd

; --------------------------------
; Section: Desktop Shortcut (optional)
; --------------------------------
Section "Desktop Shortcut" SEC_DESKTOP
  SetOutPath $INSTDIR
  CreateShortcut "$DESKTOP\${PRODUCT_NAME}.lnk" "$INSTDIR\${LAUNCHER_EXE}" "" "$INSTDIR\icon.ico" 0
SectionEnd

; --------------------------------
; Section: Start Menu Shortcut (optional)
; --------------------------------
Section "Start Menu Shortcut" SEC_STARTMENU
  SetOutPath $INSTDIR
  CreateShortcut "$SMPROGRAMS\${PRODUCT_NAME}.lnk" "$INSTDIR\${LAUNCHER_EXE}" "" "$INSTDIR\icon.ico" 0
SectionEnd

; --------------------------------
; Section: Auto-start on boot (optional)
; --------------------------------
Section "Start automatically when Windows starts" SEC_AUTOSTART
  ; Point the Run key directly at the (signed) launcher exe. Windows uses
  ; System32 as the working directory at logon, but the app re-anchors its
  ; working directory to the install folder on startup (see AppHome.anchor()),
  ; so it still finds config.json / log/ / tls/. Launching the signed exe
  ; directly — instead of via a wscript/VBS wrapper — also sidesteps Defender
  ; ASR rules that block scripts from starting executables, which is the
  ; reliable auto-start path on managed (Intune) fleets.
  WriteRegStr ${REG_ROOT} "${RUN_KEY}" "${PRODUCT_NAME}" '"$INSTDIR\${LAUNCHER_EXE}"'
SectionEnd

; --------------------------------
; Section descriptions (shown on components page)
; --------------------------------
LangString DESC_SEC_MAIN ${LANG_ENGLISH} "Core application files including a bundled Java runtime (required)"
LangString DESC_SEC_DESKTOP ${LANG_ENGLISH} "Create a shortcut on your desktop"
LangString DESC_SEC_STARTMENU ${LANG_ENGLISH} "Create a shortcut in the Start Menu"
LangString DESC_SEC_AUTOSTART ${LANG_ENGLISH} "Automatically start Local Hardware Bridge when Windows starts (runs in the system tray, no window)"

!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_MAIN} $(DESC_SEC_MAIN)
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_DESKTOP} $(DESC_SEC_DESKTOP)
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_STARTMENU} $(DESC_SEC_STARTMENU)
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_AUTOSTART} $(DESC_SEC_AUTOSTART)
!insertmacro MUI_FUNCTION_DESCRIPTION_END

; --------------------------------
; Component default selections
; --------------------------------
Function .onInit
  ; Desktop shortcut is selected by default, unless built with /DNO_DESKTOP_ICON=1
  ; (used for enterprise/Intune deployments where desktop icons are undesirable)
  !ifdef NO_DESKTOP_ICON
    !insertmacro UnselectSection ${SEC_DESKTOP}
  !else
    !insertmacro SetSectionFlag ${SEC_DESKTOP} ${SF_SELECTED}
  !endif
  !insertmacro SetSectionFlag ${SEC_STARTMENU} ${SF_SELECTED}
  !insertmacro SetSectionFlag ${SEC_AUTOSTART} ${SF_SELECTED}
FunctionEnd

; --------------------------------
; Launch app after install (windowless launcher)
; --------------------------------
Function LaunchApp
  ExecShell "" "$INSTDIR\${LAUNCHER_EXE}"
FunctionEnd

; --------------------------------
; Uninstaller
; --------------------------------
Section "Uninstall"
  ; Stop a running instance
  ExecWait 'taskkill /F /IM "${LAUNCHER_EXE}"' $0

  ; Remove auto-start
  DeleteRegValue ${REG_ROOT} "${RUN_KEY}" "${PRODUCT_NAME}"

  ; Remove registry keys
  DeleteRegKey ${REG_ROOT} "${PRODUCT_UNINST_KEY}"
  DeleteRegKey ${REG_ROOT} "${PRODUCT_REGKEY}"

  ; Delete shortcuts (LHB only — the WHB fork is intentionally left untouched)
  Delete "$DESKTOP\${PRODUCT_NAME}.lnk"
  Delete "$SMPROGRAMS\${PRODUCT_NAME}.lnk"
  Delete "$SMSTARTUP\${PRODUCT_NAME}.lnk"

  ; Remove all files and uninstaller
  RMDir /r "$INSTDIR"
SectionEnd