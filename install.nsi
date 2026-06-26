; The name of the installer
Name "Local Hardware Bridge"

; The file to write
OutFile "lhb.exe"

; The default installation directory
InstallDir "$LOCALAPPDATA\Local Hardware Bridge"

; Request application privileges for Windows Vista
RequestExecutionLevel user

;--------------------------------

; Pages
;Page directory
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
  WriteRegStr HKCU "SOFTWARE\Local Hardware Bridge" "Install_Dir" "$INSTDIR"
  
  ; Write the uninstall keys for Windows
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Local Hardware Bridge" "DisplayName" "Local Hardware Bridge"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Local Hardware Bridge" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Local Hardware Bridge" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Local Hardware Bridge" "NoRepair" 1
  WriteUninstaller "uninstall.exe"

  ; Auto close when finished
  SetAutoClose true
SectionEnd ; end the section

Section "Auto-start" autostart
  CreateShortcut "$SMSTARTUP\Local Hardware Bridge.lnk" "$INSTDIR\jre\bin\javaw.exe" "-cp local-hardware-bridge.jar io.github.augustinlr17.localhardwarebridge.GUI"
SectionEnd

Section "Uninstall"
  ; Remove registry keys
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Local Hardware Bridge"
  DeleteRegKey HKCU "SOFTWARE\Local Hardware Bridge"
  
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