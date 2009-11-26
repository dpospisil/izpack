package com.izforge.izpack.installer.data;

import com.izforge.izpack.data.*;
import com.izforge.izpack.installer.GUIInstaller;
import com.izforge.izpack.installer.InstallerException;
import com.izforge.izpack.installer.PrivilegedRunner;
import com.izforge.izpack.installer.base.InstallerBase;
import com.izforge.izpack.installer.unpacker.ScriptParser;
import com.izforge.izpack.rules.RulesEngine;
import com.izforge.izpack.util.*;
import org.picocontainer.injectors.ProviderAdapter;

import javax.swing.*;
import java.io.File;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.net.InetAddress;
import java.util.*;

/**
 * Install data loader
 */
public class InstallDataGuiProvider extends ProviderAdapter {

    /**
     * The base name of the XML file that specifies the custom langpack. Searched is for the file
     * with the name expanded by _ISO3.
     */
    protected static final String LANG_FILE_NAME = "CustomLangpack.xml";

    public InstallData provideData() throws Exception, InterruptedException {
        final InstallData installData = new InstallData();
        // Loads the installation data
        loadInstallData(installData);
        // add the GUI install data
        loadGUIInstallData(installData);
        // Load custom langpack if exist.
        addCustomLangpack(installData);

        return installData;
    }

    /**
     * Loads the installation data. Also sets environment variables to <code>installdata</code>.
     * All system properties are available as $SYSTEM_<variable> where <variable> is the actual
     * name _BUT_ with all separators replaced by '_'. Properties with null values are never stored.
     * Example: $SYSTEM_java_version or $SYSTEM_os_name
     *
     * @param installdata Where to store the installation data.
     * @throws Exception Description of the Exception
     */
    private void loadInstallData(AutomatedInstallData installdata) throws Exception {
        // Usefull variables
        InputStream in;
        ObjectInputStream objIn;
        int size;
        int i;

        // We load the variables
        Properties variables = null;
        in = InstallerBase.class.getResourceAsStream("/vars");
        if (null != in) {
            objIn = new ObjectInputStream(in);
            variables = (Properties) objIn.readObject();
            objIn.close();
        }

        // We load the Info data
        in = InstallerBase.class.getResourceAsStream("/info");
        objIn = new ObjectInputStream(in);
        Info inf = (Info) objIn.readObject();
        objIn.close();

        checkForPrivilegedExecution(inf);

        checkForRebootAction(inf);

        // We put the Info data as variables
        installdata.setVariable(ScriptParser.APP_NAME, inf.getAppName());
        if (inf.getAppURL() != null) {
            installdata.setVariable(ScriptParser.APP_URL, inf.getAppURL());
        }
        installdata.setVariable(ScriptParser.APP_VER, inf.getAppVersion());
        if (inf.getUninstallerCondition() != null) {
            installdata.setVariable("UNINSTALLER_CONDITION", inf.getUninstallerCondition());
        }

        installdata.setInfo(inf);
        // Set the installation path in a default manner
        String dir = getDir();
        String installPath = dir + inf.getAppName();
        if (inf.getInstallationSubPath() != null) { // A subpath was defined, use it.
            installPath = IoHelper.translatePath(dir + inf.getInstallationSubPath(),
                    new VariableSubstitutor(installdata.getVariables()));
        }
        installdata.setInstallPath(installPath);


        // We read the panels order data
        in = InstallerBase.class.getResourceAsStream("/panelsOrder");
        objIn = new ObjectInputStream(in);
        List<Panel> panelsOrder = (List<Panel>) objIn.readObject();
        objIn.close();


        // We read the packs data
        in = InstallerBase.class.getResourceAsStream("/packs.info");
        objIn = new ObjectInputStream(in);
        size = objIn.readInt();
        ArrayList availablePacks = new ArrayList();
        ArrayList<Pack> allPacks = new ArrayList<Pack>();
        for (i = 0; i < size; i++) {
            Pack pk = (Pack) objIn.readObject();
            allPacks.add(pk);
            if (OsConstraint.oneMatchesCurrentSystem(pk.osConstraints)) {
                availablePacks.add(pk);
            }
        }
        objIn.close();

        // We determine the hostname and IPAdress
        String hostname;
        String IPAddress;

        try {
            InetAddress addr = InetAddress.getLocalHost();

            // Get IP Address
            IPAddress = addr.getHostAddress();

            // Get hostname
            hostname = addr.getHostName();
        }
        catch (Exception e) {
            hostname = "";
            IPAddress = "";
        }


        installdata.setVariable("APPLICATIONS_DEFAULT_ROOT", dir);
        dir += File.separator;
        installdata.setVariable(ScriptParser.JAVA_HOME, System.getProperty("java.home"));
        installdata.setVariable(ScriptParser.CLASS_PATH, System.getProperty("java.class.path"));
        installdata.setVariable(ScriptParser.USER_HOME, System.getProperty("user.home"));
        installdata.setVariable(ScriptParser.USER_NAME, System.getProperty("user.name"));
        installdata.setVariable(ScriptParser.IP_ADDRESS, IPAddress);
        installdata.setVariable(ScriptParser.HOST_NAME, hostname);
        installdata.setVariable(ScriptParser.FILE_SEPARATOR, File.separator);

        Enumeration e = System.getProperties().keys();
        while (e.hasMoreElements()) {
            String varName = (String) e.nextElement();
            String varValue = System.getProperty(varName);
            if (varValue != null) {
                varName = varName.replace('.', '_');
                installdata.setVariable("SYSTEM_" + varName, varValue);
            }
        }

        if (null != variables) {
            Enumeration enumeration = variables.keys();
            String varName;
            String varValue;
            while (enumeration.hasMoreElements()) {
                varName = (String) enumeration.nextElement();
                varValue = variables.getProperty(varName);
                installdata.setVariable(varName, varValue);
            }
        }

        installdata.setPanelsOrder(panelsOrder);
        installdata.setAvailablePacks(availablePacks);
        installdata.setAllPacks(allPacks);

        // get list of preselected packs
        Iterator pack_it = availablePacks.iterator();
        while (pack_it.hasNext()) {
            Pack pack = (Pack) pack_it.next();
            if (pack.preselected) {
                installdata.getSelectedPacks().add(pack);
            }
        }

        // Load custom action data.
        loadCustomData(installdata);

    }

    /**
     * Add the contents of a custom langpack (if exist) to the previos loaded comman langpack. If
     * not exist, trace an info and do nothing more.
     *
     * @param idata install data to be used
     */
    private void addCustomLangpack(AutomatedInstallData idata) {
        // We try to load and add a custom langpack.
        try {
            idata.getLangpack().add(ResourceManager.getInstance().getInputStream(LANG_FILE_NAME));
        }
        catch (Throwable exception) {
            Debug.trace("No custom langpack available.");
            return;
        }
        Debug.trace("Custom langpack for " + idata.getLocaleISO3() + " available.");
    }


    /**
     * Loads custom data like listener and lib references if exist and fills the installdata.
     *
     * @param installdata installdata into which the custom action data should be stored
     * @throws Exception
     */
    private void loadCustomData(AutomatedInstallData installdata) throws Exception {
        // Usefull variables
        InputStream in;
        ObjectInputStream objIn;
        int i;
        // Load listeners if exist.
        String[] streamNames = AutomatedInstallData.CUSTOM_ACTION_TYPES;
        List[] out = new List[streamNames.length];
        for (i = 0; i < streamNames.length; ++i) {
            out[i] = new ArrayList();
        }
        in = InstallerBase.class.getResourceAsStream("/customData");
        if (in != null) {
            objIn = new ObjectInputStream(in);
            Object listeners = objIn.readObject();
            objIn.close();
            Iterator keys = ((List) listeners).iterator();
            while (keys != null && keys.hasNext()) {
                CustomData ca = (CustomData) keys.next();

                if (ca.osConstraints != null
                        && !OsConstraint.oneMatchesCurrentSystem(ca.osConstraints)) { // OS constraint defined, but not matched; therefore ignore
                    // it.
                    continue;
                }
                switch (ca.type) {
                    case CustomData.INSTALLER_LISTENER:
                        Class clazz = Class.forName(ca.listenerName);
                        if (clazz == null) {
                            throw new InstallerException("Custom action " + ca.listenerName
                                    + " not bound!");
                        }
                        out[ca.type].add(clazz.newInstance());
                        break;
                    case CustomData.UNINSTALLER_LISTENER:
                    case CustomData.UNINSTALLER_JAR:
                        out[ca.type].add(ca);
                        break;
                    case CustomData.UNINSTALLER_LIB:
                        out[ca.type].add(ca.contents);
                        break;
                }

            }
            // Add the current custem action data to the installdata hash map.
            for (i = 0; i < streamNames.length; ++i) {
                installdata.getCustomData().put(streamNames[i], out[i]);
            }
        }
        // uninstallerLib list if exist

    }


    private String getDir() {
        // We determine the operating system and the initial installation path
        String dir;
        if (OsVersion.IS_WINDOWS) {
            dir = buildWindowsDefaultPath();
        } else if (OsVersion.IS_OSX) {
            dir = "/Applications";
        } else {
            if (new File("/usr/local/").canWrite()) {
                dir = "/usr/local";
            } else {
                dir = System.getProperty("user.home");
            }
        }
        return dir;
    }

    private void checkForPrivilegedExecution(Info info) {
        if (PrivilegedRunner.isPrivilegedMode()) {
            // We have been launched through a privileged execution, so stop the checkings here!
            return;
        } else if (info.isPrivilegedExecutionRequired()) {
            boolean shouldElevate = true;
            final String conditionId = info.getPrivilegedExecutionConditionID();
            if (conditionId != null) {
                shouldElevate = RulesEngine.getCondition(conditionId).isTrue();
            }
            PrivilegedRunner runner = new PrivilegedRunner(!shouldElevate);
            if (runner.isPlatformSupported() && runner.isElevationNeeded()) {
                try {
                    if (runner.relaunchWithElevatedRights() == 0) {
                        System.exit(0);
                    } else {
                        throw new RuntimeException("Launching an installer with elevated permissions failed.");
                    }
                }
                catch (Exception e) {
                    e.printStackTrace();
                    JOptionPane.showMessageDialog(null, "The installer could not launch itself with administrator permissions.\n" +
                            "The installation will still continue but you may encounter problems due to insufficient permissions.");
                }
            } else if (!runner.isPlatformSupported()) {
                JOptionPane.showMessageDialog(null, "This installer should be run by an administrator.\n" +
                        "The installation will still continue but you may encounter problems due to insufficient permissions.");
            }
        }

    }

    private void checkForRebootAction(Info info) {
        final String conditionId = info.getRebootActionConditionID();
        if (conditionId != null) {
            if (!RulesEngine.getCondition(conditionId).isTrue())
                info.setRebootAction(Info.REBOOT_ACTION_IGNORE);
        }
    }

    /**
     * Get the default path for Windows (i.e Program Files/...).
     * Windows has a Setting for this in the environment and in the registry.
     * Just try to use the setting in the environment. If it fails for whatever reason, we take the former solution (buildWindowsDefaultPathFromProps).
     *
     * @return The Windows default installation path for applications.
     */
    private String buildWindowsDefaultPath() {
        try {
            //get value from environment...
            String prgFilesPath = IoHelper.getenv("ProgramFiles");
            if (prgFilesPath != null && prgFilesPath.length() > 0) {
                return prgFilesPath;
            } else {
                return buildWindowsDefaultPathFromProps();
            }
        }
        catch (Exception x) {
            x.printStackTrace();
            return buildWindowsDefaultPathFromProps();
        }
    }

    /**
     * just plain wrong in case the programfiles are not stored where the developer expects them.
     * E.g. in custom installations of large companies or if used internationalized version of windows with a language pack.
     *
     * @return the program files path
     */
    private String buildWindowsDefaultPathFromProps() {
        StringBuffer dpath = new StringBuffer("");
        try {
            // We load the properties
            Properties props = new Properties();
            props
                    .load(InstallerBase.class
                            .getResourceAsStream("/com/izforge/izpack/installer/win32-defaultpaths.properties"));

            // We look for the drive mapping
            String drive = System.getProperty("user.home");
            if (drive.length() > 3) {
                drive = drive.substring(0, 3);
            }

            // Now we have it :-)
            dpath.append(drive);

            // Ensure that we have a trailing backslash (in case drive was
            // something
            // like "C:")
            if (drive.length() == 2) {
                dpath.append("\\");
            }

            String language = Locale.getDefault().getLanguage();
            String country = Locale.getDefault().getCountry();
            String language_country = language + "_" + country;

            // Try the most specific combination first
            if (null != props.getProperty(language_country)) {
                dpath.append(props.getProperty(language_country));
            } else if (null != props.getProperty(language)) {
                dpath.append(props.getProperty(language));
            } else {
                dpath.append(props.getProperty(Locale.ENGLISH.getLanguage()));
            }
        }
        catch (Exception err) {
            dpath = new StringBuffer("C:\\Program Files");
        }

        return dpath.toString();
    }


    /**
     * Load GUI preference information.
     *
     * @param installdata
     * @throws Exception
     */
    private void loadGUIInstallData(InstallData installdata) throws Exception {
        InputStream in = GUIInstaller.class.getResourceAsStream("/GUIPrefs");
        ObjectInputStream objIn = new ObjectInputStream(in);
        installdata.guiPrefs = (GUIPrefs) objIn.readObject();
        objIn.close();
    }


}
