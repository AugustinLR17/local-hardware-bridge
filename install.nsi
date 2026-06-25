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
  
  ; Remove old version
  RMDir /r "$INSTDIR\jre"
  Delete "$INSTDIR\*.jar"
  Delete "$INSTDIR\setting.default.json"
  Delete "$DESKTOP\Local Hardware Bridge (GUI).lnk"
  Delete "$DESKTOP\Local Hardware Bridge (Configurator).lnk"
  Delete "$SMPROGRAMS\Local Hardware Bridge (GUI).lnk"
  Delete "$SMPROGRAMS\Local Hardware Bridge (Configurator).lnk"
  
  ; Remove old TigerWorkshop shortcuts
  Delete "$DESKTOP\WebApp Hardware Bridge.lnk"
  Delete "$DESKTOP\WebApp Hardware Bridge (CLI).lnk"
  Delete "$SMPROGRAMS\WebApp Hardware Bridge.lnk"
  Delete "$SMPROGRAMS\WebApp Hardware Bridge (CLI).lnk"
  
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
  
  ; Create shortcuts
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
  
  ; Delete shortcuts
  Delete "$DESKTOP\Local Hardware Bridge.lnk"
  Delete "$DESKTOP\Local Hardware Bridge (CLI).lnk"
  Delete "$SMPROGRAMS\Local Hardware Bridge.lnk"
  Delete "$SMPROGRAMS\Local Hardware Bridge (CLI).lnk"
  
  ; Remove files and uninstaller
  RMDir /r $INSTDIR
SectionEnd

Function .onInstSuccess
  ExecShell "" "$DESKTOP\Local Hardware Bridge.lnk"
FunctionEnd
