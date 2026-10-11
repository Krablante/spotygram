; Package the existing jpackage image. Application data stays in the separate profile.
[Setup]
AppId={{58e4eaa7-5519-5da3-8f61-6e1dc41508c8}
AppName=Spotygram
AppVersion={#AppVersion}
AppPublisher=Krablante
AppPublisherURL=https://github.com/Krablante/spotygram
AppSupportURL=https://github.com/Krablante/spotygram/issues
DefaultDirName={code:InstallationDirectory}
DefaultGroupName=Spotygram
DisableProgramGroupPage=yes
DisableDirPage=no
PrivilegesRequired=lowest
ArchitecturesAllowed=x64os
ArchitecturesInstallIn64BitMode=x64os
MinVersion=10.0
WizardStyle=modern
SetupIconFile={#AppIcon}
UninstallDisplayIcon={app}\Spotygram.exe
LicenseFile={#LicenseFile}
OutputDir={#OutputDir}
OutputBaseFilename=Spotygram-{#AppVersion}
Compression=lzma2/normal
LZMANumBlockThreads=2
SolidCompression=yes
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#AppImage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\Spotygram\Spotygram"; Filename: "{app}\Spotygram.exe"
Name: "{userdesktop}\Spotygram"; Filename: "{app}\Spotygram.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Spotygram.exe"; Description: "{cm:LaunchProgram,Spotygram}"; Flags: nowait postinstall skipifsilent

[CustomMessages]
english.LegacyBusy=Finish or cancel the current Windows installation, then retry.
russian.LegacyBusy=Заверши или отмени текущую установку Windows и повтори попытку.
english.LegacyFailure=Could not remove the older Spotygram installation. Quit Spotygram and retry. Details: %1
russian.LegacyFailure=Не удалось удалить прежнюю установку Spotygram. Закрой Spotygram и повтори попытку. Подробности: %1
english.LegacyRestart=Restart Windows to finish removing the older Spotygram installation, then run setup again.
russian.LegacyRestart=Перезапусти Windows, чтобы завершить удаление прежней установки Spotygram, затем снова запусти установщик.

[Code]
const
  LegacyUpgradeCode = '{58E4EAA7-5519-5DA3-8F61-6E1DC41508C8}';
var
  LegacyProducts: TArrayOfString;
  LegacyDirectory: String;
  PreviousConfiguration: TArrayOfString;

function MsiEnumRelatedProducts(UpgradeCode: String; Reserved, Index: Cardinal;
  ProductCode: String): Cardinal;
  external 'MsiEnumRelatedProductsW@msi.dll stdcall setuponly';
function MsiGetProductInfo(ProductCode, PropertyName, Value: String;
  var Size: Cardinal): Cardinal;
  external 'MsiGetProductInfoW@msi.dll stdcall setuponly';

function ProductProperty(ProductCode, PropertyName: String): String;
var
  Size, Status: Cardinal;
  Buffer: String;
begin
  Result := '';
  Size := 0;
  Status := MsiGetProductInfo(ProductCode, PropertyName, '', Size);
  if (Status <> 0) and (Status <> 234) then Exit;
  if (Size = 0) or (Size > 32767) then Exit;
  Buffer := StringOfChar(#0, Size + 1);
  Size := Size + 1;
  if MsiGetProductInfo(ProductCode, PropertyName, Buffer, Size) = 0 then
    Result := Copy(Buffer, 1, Size);
end;

function InitializeSetup(): Boolean;
var
  Index, Count: Cardinal;
  Buffer, ProductCode: String;
begin
  Result := True;
  Index := 0;
  Buffer := StringOfChar(#0, 39);
  while MsiEnumRelatedProducts(LegacyUpgradeCode, 0, Index, Buffer) = 0 do begin
    ProductCode := Copy(Buffer, 1, 38);
    Log('Found legacy MSI product ' + ProductCode);
    if (ProductProperty(ProductCode, 'InstalledProductName') = 'Spotygram') and
       (ProductProperty(ProductCode, 'Publisher') = 'Krablante') then begin
      Count := GetArrayLength(LegacyProducts);
      SetArrayLength(LegacyProducts, Count + 1);
      LegacyProducts[Count] := ProductCode;
      if LegacyDirectory = '' then
        LegacyDirectory := ProductProperty(ProductCode, 'InstallLocation');
    end;
    Index := Index + 1;
  end;
end;

function InstallationDirectory(Param: String): String;
begin
  Result := ExpandConstant('{localappdata}\SpotygramApp');
  if LegacyDirectory <> '' then Result := LegacyDirectory;
end;

function RollbackSecurityFailure(LogFile: String): Boolean;
var
  Bytes: AnsiString;
  Text: String;
begin
  Result := False;
  if not LoadStringFromFile(LogFile, Bytes) then Exit;
  Text := String(Bytes);
  // MSI writes UTF-16 logs; the error numbers and Config.Msi name are ASCII.
  StringChangeEx(Text, #0, '', True);
  Text := Lowercase(Text);
  Result := (Pos('config.msi', Text) > 0) and
    ((Pos('error 1926.', Text) > 0) or (Pos('error 1314.', Text) > 0));
end;

function RemoveLegacy(ProductCode, LogFile, Properties: String): Integer;
begin
  Result := -1;
  Exec(ExpandConstant('{sys}\msiexec.exe'), '/x ' + ProductCode +
    ' /qn /norestart /L*v ' + AddQuotes(LogFile) + ' ' + Properties,
    '', SW_HIDE, ewWaitUntilTerminated, Result);
end;

function ContainsLine(Lines: TArrayOfString; Value: String): Boolean;
var
  Index: Integer;
begin
  Result := False;
  for Index := 0 to GetArrayLength(Lines) - 1 do
    if Lines[Index] = Value then begin Result := True; Exit; end;
end;

procedure CurStepChanged(Step: TSetupStep);
var
  CurrentConfiguration: TArrayOfString;
  Index: Integer;
  Prefix, Name: String;
begin
  if Step <> ssPostInstall then Exit;
  if not ContainsLine(PreviousConfiguration, 'app.mainclass=app.spotygram.DesktopMainKt') then Exit;
  if not LoadStringsFromFile(ExpandConstant('{app}\app\Spotygram.cfg'), CurrentConfiguration) then Exit;
  Prefix := 'app.classpath=$APPDIR\';
  for Index := 0 to GetArrayLength(PreviousConfiguration) - 1 do begin
    if (Pos(Prefix, PreviousConfiguration[Index]) = 1) and
       not ContainsLine(CurrentConfiguration, PreviousConfiguration[Index]) then begin
      Name := Copy(PreviousConfiguration[Index], Length(Prefix) + 1, MaxInt);
      if (ExtractFileName(Name) = Name) and (ExtractFileExt(Name) = '.jar') then
        if DeleteFile(ExpandConstant('{app}\app\') + Name) then
          Log('Removed obsolete application library ' + Name);
    end;
  end;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Index, ExitCode: Integer;
  Directory, LogFile: String;
begin
  Result := '';
  // A failed MSI removal can leave program files after losing its registration.
  LoadStringsFromFile(ExpandConstant('{app}\app\Spotygram.cfg'), PreviousConfiguration);
  if GetArrayLength(LegacyProducts) = 0 then Exit;
  Directory := ExpandConstant('{localappdata}\Spotygram\cache\installer');
  if not ForceDirectories(Directory) then begin
    Result := FmtMessage(CustomMessage('LegacyFailure'), [Directory]);
    Exit;
  end;
  for Index := 0 to GetArrayLength(LegacyProducts) - 1 do begin
    LogFile := Directory + '\legacy-' + LegacyProducts[Index] + '.log';
    ExitCode := RemoveLegacy(LegacyProducts[Index], LogFile, '');
    if (ExitCode <> 0) and RollbackSecurityFailure(LogFile) then begin
      // Retry only this legacy removal after its rollback-security failure.
      // No Windows policy or Config.Msi permissions are changed.
      LogFile := Directory + '\legacy-' + LegacyProducts[Index] + '-retry.log';
      ExitCode := RemoveLegacy(LegacyProducts[Index], LogFile, 'DISABLEROLLBACK=1');
    end;
    if ExitCode = 3010 then begin
      NeedsRestart := True;
      Result := CustomMessage('LegacyRestart');
      Exit;
    end;
    if ExitCode = 1618 then begin
      Result := CustomMessage('LegacyBusy');
      Exit;
    end;
    if (ExitCode <> 0) and (ExitCode <> 1605) and (ExitCode <> 1614) then begin
      Result := FmtMessage(CustomMessage('LegacyFailure'), [LogFile]);
      Exit;
    end;
  end;
end;
