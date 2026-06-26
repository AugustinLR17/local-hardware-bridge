; Local Hardware Bridge NSIS Installer
; Version metadata — this is what Windows shows in "Properties > Details"
!define PRODUCT_NAME "Local Hardware Bridge"
!define PRODUCT_VERSION "1.0.1"
!define PRODUCT_PUBLISHER "AugustinLR17"
!define PRODUCT_URL "https://github.com/AugustinLR17/local-hardware-bridge"
!define PRODUCT_UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\Local Hardware Bridge"

; The name of the installer
Name "${PRODUCT_NAME}"

; The file to write
OutFile "lhb.exe"

; The default installation directory
InstallDir "$LOCALAPPDATA\${PRODUCT_NAME}"

; Request application privileges for Windows Vista+
RequestExecutionLevel user

; Version info embedded in the EXE — reduces SmartScreen distrust
VIProductVersion "${PRODUCT_VERSION}.0"
VIAddVersionKey "ProductName" "${PRODUCT_NAME}"
VIAddVersionKey "CompanyName" "${PRODUCT_PUBLISHER}"
VIAddVersionKey "FileDescription" "${PRODUCT_NAME} Installer"
VIAddVersionKey "FileVersion" "${PRODUCT_VERSION}"
VIAddVersionKey "ProductVersion" "${PRODUCT_VERSION}"
VIAddVersionKey "LegalCopyright" "Copyright (C) 2024-2026 ${PRODUCT_PUBLISHER}"
VIAddVersionKey "OriginalFilename" "lhb.exe"

;--------------------------------

; Pages
Page components
Page instfiles

;--------------------------------

; The stuff to install
Section "!Main Application" ;No components page, name is not important
  SectionIn RO

  ; Set output path to the installation directory.
  SetOutPath $INSTDIR
  
  ; Remove old version (Local Hardware Bridge)
  RMDir /r "$INSTDIR\jre"
  Delete "$INSTDIR\*.jar"
  Delete "$INSTDIR\setting.default.json"
  Delete "$DESKTOP\Local Hardware Bridge (GUI).lnk"
  Delete "$DESKTOP\Local Hardware Bridge (Configurator).lnk"
  Delete "$SMPROGRAMS\Local Hardware Bridge (GUI).lnk"
  Delete "$SMPROGRAMS\Local Hardware Bridge (Configurator).lnk"
  
  ; Remove old TigerWorkshop shortcuts (migration from original fork)
  Delete "$DESKTOP\WebApp Hardware Bridge.lnk"
  Delete "$DESKTOP\WebApp Hardware Bridge (CLI).lnk"
  Delete "$SMPROGRAMS\WebApp Hardware Bridge.lnk"
  Delete "$SMPROGRAMS\WebApp Hardware Bridge (CLI).lnk"
  
  ; Remove old TigerWorkshop auto-start shortcut
  Delete "$SMSTARTUP\WebApp Hardware Bridge.lnk"

  ; Remove old TigerWorkshop registry keys (migration)
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\WebApp Hardware Bridge"
  DeleteRegKey HKCU "SOFTWARE\WebApp Hardware Bridge"
  
  ; Put file there
  File /r out\artifacts\webapp_hardware_bridge_jar\*
  File /r jre
  
  File "install.nsi"
  File "icon.ico"
  
  ; Delete old shortcuts  
  Delete "$DESKTOP\Local Hardware Bridge.lnk"
  Delete "$DESKTOP\Local Hardware Bridge (CLI).lnk"
  Delete "$SMPROGRAMS\Local Hardware Bridge.lnk"
  Delete "$SMPROGRAMS\Local Hardware Bridge (CLI).lnk"
  
  ; Create shortcuts — use javaw.exe for GUI (no console window) and java.exe for CLI Server
  CreateShortcut "$DESKTOP\Local Hardware Bridge.lnk" "$INSTDIR\jre\bin\javaw.exe" "-cp local-hardware-bridge.jar io.github.augustinlr17.localhardwarebridge.GUI" "$INSTDIR\icon.ico" 0
  CreateShortcut "$DESKTOP\Local Hardware Bridge (CLI).lnk" "$INSTDIR\jre\bin\java.exe" "-cp local-hardware-bridge.jar io.github.augustinlr17.localhardwarebridge.Server" "$INSTDIR\icon.ico" 0
  CreateShortcut "$SMPROGRAMS\Local Hardware Bridge.lnk" "$INSTDIR\jre\bin\javaw.exe" "-cp local-hardware-bridge.jar io.github.augustinlr17.localhardwarebridge.GUI" "$INSTDIR\icon.ico" 0
  CreateShortcut "$SMPROGRAMS\Local Hardware Bridge (CLI).lnk" "$INSTDIR\jre\bin\java.exe" "-cp local-hardware-bridge.jar io.github.augustinlr17.localhardwarebridge.Server" "$INSTDIR\icon.ico" 0

  ; Write the installation path into the registry
  WriteRegStr HKCU "SOFTWARE\${PRODUCT_NAME}" "Install_Dir" "$INSTDIR"
  
  ; Write the uninstall keys for Windows — with full version metadata
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "DisplayName" "${PRODUCT_NAME}"
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "DisplayVersion" "${PRODUCT_VERSION}"
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "Publisher" "${PRODUCT_PUBLISHER}"
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "URLInfoAbout" "${PRODUCT_URL}"
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "DisplayIcon" '"$INSTDIR\icon.ico"'
  WriteRegDWORD HKCU "${PRODUCT_UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${PRODUCT_UNINST_KEY}" "NoRepair" 1
  WriteRegStr HKCU "${PRODUCT_UNINST_KEY}" "EstimatedSize" "150000"
  WriteUninstaller "uninstall.exe"

  ; Auto close when finished
  SetAutoClose true
SectionEnd ; end the section

Section "Auto-start" autostart
  ; Register in HKCU\...\Run for auto-start (more reliable than Startup folder)
  WriteRegStr HKCU "SOFTWARE\Microsoft\Windows\CurrentVersion\Run" "${PRODUCT_NAME}" '"$INSTDIR\jre\bin\javaw.exe" -cp "$INSTDIR\local-hardware-bridge.jar" io.github.augustinlr17.localhardwarebridge.GUI'
  ; Also create Startup folder shortcut as backup
  CreateShortcut "$SMSTARTUP\Local Hardware Bridge.lnk" "$INSTDIR\jre\bin\javaw.exe" "-cp local-hardware-bridge.jar io.github.augustinlr17.localhardwarebridge.GUI" "$INSTDIR\icon.ico" 0
SectionEnd

Section "Uninstall"
  ; Remove auto-start registry entry
  DeleteRegValue HKCU "SOFTWARE\Microsoft\Windows\CurrentVersion\Run" "${PRODUCT_NAME}"
  
  ; Remove registry keys
  DeleteRegKey HKCU "${PRODUCT_UNINST_KEY}"
  DeleteRegKey HKCU "SOFTWARE\${PRODUCT_NAME}"
  
  ; Also clean up legacy registry keys if they still exist
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\WebApp Hardware Bridge"
  DeleteRegKey HKCU "SOFTWARE\WebApp Hardware Bridge"
  
  ; Delete shortcuts
  Delete "$DESKTOP\Local Hardware Bridge.lnk"
  Delete "$DESKTOP\Local Hardware Bridge (CLI).lnk"
  Delete "$SMPROGRAMS\Local Hardware Bridge.lnk"
  Delete "$SMPROGRAMS\Local Hardware Bridge (CLI).lnk"
  Delete "$SMSTARTUP\Local Hardware Bridge.lnk"
  
  ; Also delete legacy shortcuts
  Delete "$DESKTOP\WebApp Hardware Bridge.lnk"
  Delete "$DESKTOP\WebApp Hardware Bridge (CLI).lnk"
  Delete "$SMPROGRAMS\WebApp Hardware Bridge.lnk"
  Delete "$SMPROGRAMS\WebApp Hardware Bridge (CLI).lnk"
  Delete "$SMSTARTUP\WebApp Hardware Bridge.lnk"
  
  ; Remove files and uninstaller
  RMDir /r $INSTDIR
SectionEnd

Function .onInstSuccess
  ExecShell "" "$DESKTOP\Local Hardware Bridge.lnk"
FunctionEnd