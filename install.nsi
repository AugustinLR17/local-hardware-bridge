; Local Hardware Bridge — NSIS Installer
; ==========================================
; Modern UI installer with custom branding, optional components, and
; auto-start registration via HKCU\...\Run (more reliable than Startup folder).

; --------------------------------
; Includes
; --------------------------------
!include MUI2.nsh
!include LogicLib.nsh

; --------------------------------
; Defines
; --------------------------------
!define PRODUCT_NAME "Local Hardware Bridge"
!define PRODUCT_VERSION "1.0.1"
!define PRODUCT_PUBLISHER "AugustinLR17"
!define PRODUCT_URL "https://github.com/AugustinLR17/local-hardware-bridge"
!define PRODUCT_UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\Local Hardware Bridge"
!define PRODUCT_REGKEY "SOFTWARE\${PRODUCT_NAME}"

; --------------------------------
; Installer attributes
; --------------------------------
Name "${PRODUCT_NAME}"
OutFile "lhb.exe"
InstallDir "$LOCALAPPDATA\${PRODUCT_NAME}"
RequestExecutionLevel user

; Version info embedded in the EXE
VIProductVersion "${PRODUCT_VERSION}.0"
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
; Header / footer images (150x57 BMP for header, 150x70 BMP for wizard image)
; If you have branding images, uncomment and place them in the project root:
; !define MUI_HEADERIMAGE
; !define MUI_HEADERIMAGE_BITMAP "header.bmp"
; !define MUI_WELCOMEFINISHPAGE_BITMAP "wizard.bmp"
; !define MUI_UNWELCOMEFINISHPAGE_BITMAP "wizard.bmp"

!define MUI_ICON "icon.ico"
!define MUI_UNICON "icon.ico"

; Show license page
!define MUI_LICENSEPAGE_CHECKBOX
!insertmacro MUI_PAGE_LICENSE "LICENSE"

; Components page (optional features)
!insertmacro MUI_PAGE_COMPONENTS

; Directory selection
!insertmacro MUI_PAGE_DIRECTORY

; Install progress
!insertmacro MUI_PAGE_INSTFILES

; Finish page — offer to launch app
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

  SetOutPath $INSTDIR

  ; Remove old Local Hardware Bridge files
  RMDir /r "$INSTDIR\jre"
  Delete "$INSTDIR\*.jar"
  Delete "$INSTDIR\setting.default.json"

  ; Remove old shortcuts (from previous installs)
  Delete "$DESKTOP\Local Hardware Bridge.lnk"
  Delete "$DESKTOP\Local Hardware Bridge (CLI).lnk"
  Delete "$DESKTOP\Local Hardware Bridge (GUI).lnk"
  Delete "$DESKTOP\Local Hardware Bridge (Configurator).lnk"
  Delete "$SMPROGRAMS\Local Hardware Bridge.lnk"
  Delete "$SMPROGRAMS\Local Hardware Bridge (CLI).lnk"
  Delete "$SMPROGRAMS\Local Hardware Bridge (GUI).lnk"
  Delete "$SMPROGRAMS\Local Hardware Bridge (Configurator).lnk"

  ; Remove old TigerWorkshop shortcuts (migration from original fork)
  Delete "$DESKTOP\WebApp Hardware Bridge.lnk"
  Delete "$DESKTOP\WebApp Hardware Bridge (CLI).lnk"
  Delete "$SMPROGRAMS\WebApp Hardware Bridge.lnk"
  Delete "$SMPROGRAMS\WebApp Hardware Bridge (CLI).lnk"
  Delete "$SMSTARTUP\WebApp Hardware Bridge.lnk"

  ; Remove old TigerWorkshop registry keys
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\WebApp Hardware Bridge"
  DeleteRegKey HKCU "SOFTWARE\WebApp Hardware Bridge"

  ; Install files
  File /r "out\artifacts\webapp_hardware_bridge_jar\*"
  File /r "jre"
  File "icon.ico"

  ; Write registry
  WriteRegStr HKCU "${PRODUCT_REGKEY}" "Install_Dir" "$INSTDIR"
  WriteRegStr HKCU "${PRODUCT_REGKEY}" "Version" "${PRODUCT_VERSION}"

  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "DisplayName" "${PRODUCT_NAME}"
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "DisplayVersion" "${PRODUCT_VERSION}"
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "Publisher" "${PRODUCT_PUBLISHER}"
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "URLInfoAbout" "${PRODUCT_URL}"
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "DisplayIcon" '"$INSTDIR\icon.ico"'
  WriteRegDWORD HKCU "${PRODUCT_UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${PRODUCT_UNINST_KEY}" "NoRepair" 1
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "EstimatedSize" "150000"

  WriteUninstaller "$INSTDIR\uninstall.exe"

  SetAutoClose true
SectionEnd

; --------------------------------
; Section: Desktop Shortcut (optional)
; --------------------------------
Section "Desktop Shortcut" SEC_DESKTOP
  CreateShortcut "$DESKTOP\Local Hardware Bridge.lnk" "$INSTDIR\jre\bin\javaw.exe" "-cp local-hardware-bridge.jar io.github.augustinlr17.localhardwarebridge.GUI" "$INSTDIR\icon.ico" 0
SectionEnd

; --------------------------------
; Section: Start Menu Shortcut (optional)
; --------------------------------
Section "Start Menu Shortcut" SEC_STARTMENU
  CreateShortcut "$SMPROGRAMS\Local Hardware Bridge.lnk" "$INSTDIR\jre\bin\javaw.exe" "-cp local-hardware-bridge.jar io.github.augustinlr17.localhardwarebridge.GUI" "$INSTDIR\icon.ico" 0
  CreateShortcut "$SMPROGRAMS\Local Hardware Bridge (Server CLI).lnk" "$INSTDIR\jre\bin\java.exe" "-cp local-hardware-bridge.jar io.github.augustinlr17.localhardwarebridge.Server" "$INSTDIR\icon.ico" 0
SectionEnd

; --------------------------------
; Section: Auto-start on boot (optional)
; --------------------------------
Section "Start automatically when Windows starts" SEC_AUTOSTART
  ; Register in HKCU\...\Run (most reliable method)
  WriteRegStr HKCU "SOFTWARE\Microsoft\Windows\CurrentVersion\Run" "${PRODUCT_NAME}" '"$INSTDIR\jre\bin\javaw.exe" -cp "$INSTDIR\local-hardware-bridge.jar" io.github.augustinlr17.localhardwarebridge.GUI'
  ; Also create Startup folder shortcut as backup
  CreateShortcut "$SMSTARTUP\Local Hardware Bridge.lnk" "$INSTDIR\jre\bin\javaw.exe" "-cp local-hardware-bridge.jar io.github.augustinlr17.localhardwarebridge.GUI" "$INSTDIR\icon.ico" 0
SectionEnd

; --------------------------------
; Section descriptions (shown on components page)
; --------------------------------
LangString DESC_SEC_MAIN ${LANG_ENGLISH} "Core application files (required)"
LangString DESC_SEC_DESKTOP ${LANG_ENGLISH} "Create a shortcut on your desktop"
LangString DESC_SEC_STARTMENU ${LANG_ENGLISH} "Create shortcuts in the Start Menu"
LangString DESC_SEC_AUTOSTART ${LANG_ENGLISH} "Automatically start Local Hardware Bridge when Windows starts (runs in system tray)"

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
  ; Desktop shortcut: checked by default
  !insertmacro SetSectionFlag ${SEC_DESKTOP} ${SF_SELECTED}
  ; Start Menu shortcut: checked by default
  !insertmacro SetSectionFlag ${SEC_STARTMENU} ${SF_SELECTED}
  ; Auto-start: checked by default
  !insertmacro SetSectionFlag ${SEC_AUTOSTART} ${SF_SELECTED}
FunctionEnd

; --------------------------------
; Launch app after install
; --------------------------------
Function LaunchApp
  ExecShell "" "$INSTDIR\jre\bin\javaw.exe" "-cp local-hardware-bridge.jar io.github.augustinlr17.localhardwarebridge.GUI"
FunctionEnd

; --------------------------------
; Uninstaller
; --------------------------------
Section "Uninstall"
  ; Remove auto-start
  DeleteRegValue HKCU "SOFTWARE\Microsoft\Windows\CurrentVersion\Run" "${PRODUCT_NAME}"

  ; Remove registry keys
  DeleteRegKey HKCU "${PRODUCT_UNINST_KEY}"
  DeleteRegKey HKCU "${PRODUCT_REGKEY}"

  ; Clean up legacy registry keys
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\WebApp Hardware Bridge"
  DeleteRegKey HKCU "SOFTWARE\WebApp Hardware Bridge"

  ; Delete shortcuts
  Delete "$DESKTOP\Local Hardware Bridge.lnk"
  Delete "$SMPROGRAMS\Local Hardware Bridge.lnk"
  Delete "$SMPROGRAMS\Local Hardware Bridge (Server CLI).lnk"
  Delete "$SMSTARTUP\Local Hardware Bridge.lnk"

  ; Delete legacy shortcuts
  Delete "$DESKTOP\WebApp Hardware Bridge.lnk"
  Delete "$DESKTOP\WebApp Hardware Bridge (CLI).lnk"
  Delete "$SMPROGRAMS\WebApp Hardware Bridge.lnk"
  Delete "$SMPROGRAMS\WebApp Hardware Bridge (CLI).lnk"
  Delete "$SMSTARTUP\WebApp Hardware Bridge.lnk"

  ; Remove all files and uninstaller
  RMDir /r "$INSTDIR"
SectionEnd