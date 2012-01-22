package com.izforge.izpack.installer.data;

import com.izforge.izpack.api.data.AutomatedInstallData;
import com.izforge.izpack.api.exception.IzPackException;
import com.izforge.izpack.api.merge.Mergeable;
import com.izforge.izpack.api.rules.RulesEngine;
import com.izforge.izpack.api.substitutor.VariableSubstitutor;
import com.izforge.izpack.data.CustomData;
import com.izforge.izpack.data.ExecutableFile;
import com.izforge.izpack.merge.resolve.PathResolver;
import com.izforge.izpack.util.Debug;
import com.izforge.izpack.util.IoHelper;
import com.izforge.izpack.util.PrivilegedRunner;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Writes uninstall data to an executable jar file.
 */
public class UninstallDataWriter
{
    /**
     * Log file path variable name.
     */
    private static final String LOGFILE_PATH = "InstallerFrame.logfilePath";

    /**
     * Uninstall data.
     */
    private UninstallData uninstallData;

    /**
     * Install data.
     */
    private AutomatedInstallData installData;

    /**
     * The variable substitutor.
     */
    private VariableSubstitutor variableSubstitutor;

    /**
     * The path resolver.
     */
    private PathResolver pathResolver;

    /**
     * The jar to write to.
     */
    private JarOutputStream jar;

    /**
     * The rules engine.
     */
    private RulesEngine rules;

    /**
     * Constructs an <tt>UninstallDataWriter</tt>.
     *
     * @param variableSubstitutor the variable substitutor
     * @param uninstallData       the uninstall data
     * @param installData         the install data
     * @param pathResolver        the path resolver
     * @param rules               the rules engine
     */
    public UninstallDataWriter(VariableSubstitutor variableSubstitutor, UninstallData uninstallData,
                               AutomatedInstallData installData, PathResolver pathResolver, RulesEngine rules)
    {
        this.variableSubstitutor = variableSubstitutor;
        this.uninstallData = uninstallData;
        this.installData = installData;
        this.pathResolver = pathResolver;
        this.rules = rules;
    }

    /**
     * Writes the uninstall data.
     *
     * @return true if the uninstall data was written successfully, otherwise <tt>false</tt>
     */
    public boolean write()
    {
        if (!isUninstallShouldBeWriten())
        {
            return false;
        }
        try
        {
            BufferedWriter extLogWriter = getExternalLogFile();
            createOutputJar();

            System.out.println("[ Writing the uninstaller data ... ]");

            writeJarSkeleton();
            writeFilesLog(extLogWriter);
            writeUninstallerJarFileLog();
            writeExecutables();
            writeUninstallerListeners();
            writeJars();
            writeNativeLibraries();
            writeAdditionalUninstallData();
            writeScriptFiles();

            // Cleanup
            jar.flush();
            jar.close();
            return true;
        }
        catch (Exception exception)
        {
            Debug.error(exception);
            return false;
        }
    }

    /**
     * Determines if uninstall data should be written.
     * TODO - this method should not exist in this class.
     *
     * @return <tt>true</tt> if uninstall data should be written, otherwise <tt>false</tt>
     */
    public boolean isUninstallShouldBeWriten()
    {
        String condition = installData.getInfo().getUninstallerCondition();
        return condition == null || condition.length() == 0 || rules.isConditionTrue(condition);
    }

    /**
     * Determines if an external log file should be written.
     *
     * @return the external log file writer, or <tt>null</tt> if none should be written or the file couldn't be created
     */
    private BufferedWriter getExternalLogFile()
    {
        String logfile = installData.getVariable(LOGFILE_PATH);
        BufferedWriter writer = null;
        if (logfile != null)
        {
            if (logfile.toLowerCase().startsWith("default"))
            {
                logfile = installData.getInfo().getUninstallerPath() + "/install.log";
            }
            logfile = IoHelper.translatePath(logfile, variableSubstitutor);
            File outFile = new File(logfile);
            if (!outFile.getParentFile().exists())
            {
                if (!outFile.getParentFile().mkdirs())
                {
                    Debug.log("Failed to create directory: " + outFile.getParentFile().getPath());
                }
            }
            FileOutputStream out = null;
            try
            {
                out = new FileOutputStream(outFile);
            }
            catch (FileNotFoundException e)
            {
                Debug.trace("Cannot create logfile!");
                Debug.error(e);
            }
            if (out != null)
            {
                writer = new BufferedWriter(new OutputStreamWriter(out));
            }
        }

        return writer;
    }

    /**
     * Writes the uninstaller skeleton.
     *
     * @throws IOException for any I/O error
     */
    private void writeJarSkeleton() throws IOException
    {
        List<Mergeable> uninstallerMerge = pathResolver.getMergeableFromPath("com/izforge/izpack/uninstaller/");
        uninstallerMerge.addAll(pathResolver.getMergeableFromPath("uninstaller-META-INF/", "META-INF/"));
        uninstallerMerge.addAll(pathResolver.getMergeableFromPath("com/izforge/izpack/api/"));
        uninstallerMerge.addAll(pathResolver.getMergeableFromPath("com/izforge/izpack/util/"));
        uninstallerMerge.addAll(pathResolver.getMergeableFromPath("com/izforge/izpack/gui/"));
        uninstallerMerge.addAll(pathResolver.getMergeableFromPath("com/izforge/izpack/img/"));
        if (!uninstallData.getUninstallerListeners().isEmpty())
        {
            uninstallerMerge.addAll(pathResolver.getMergeableFromPath("com/izforge/izpack/event"));
        }

        for (Mergeable mergeable : uninstallerMerge)
        {
            mergeable.merge(jar);
        }

        // Should we relaunch the uninstaller with privileges?
        if (PrivilegedRunner.isPrivilegedMode() && installData.getInfo().isPrivilegedExecutionRequiredUninstaller())
        {
            jar.putNextEntry(new JarEntry("exec-admin"));
            jar.closeEntry();

            if (installData.getRules().isConditionTrue("izpack.macinstall"))
            {
                writeResource("com/izforge/izpack/installer/run-with-privileges-on-osx");
            }
        }

        // We put the langpack
        List<Mergeable> langPack = pathResolver.getMergeableFromPath("resources/langpacks/"
                + installData.getLocaleISO3() + ".xml", "langpack.xml");
        for (Mergeable mergeable : langPack)
        {
            mergeable.merge(jar);
        }
    }

    /**
     * Writes the file log.
     *
     * @param extLogWriter the external log writer. May be <tt>null</tt>
     * @throws IOException for any I/O error
     */
    private void writeFilesLog(BufferedWriter extLogWriter) throws IOException
    {
        List<String> files = uninstallData.getUninstalableFilesList();

        jar.putNextEntry(new JarEntry("install.log"));
        BufferedWriter logWriter = new BufferedWriter(new OutputStreamWriter(jar));
        logWriter.write(installData.getInstallPath());
        logWriter.newLine();
        Iterator<String> iter = files.iterator();
        if (extLogWriter != null)
        {
            // Write intern (in uninstaller.jar) and extern log file.
            while (iter.hasNext())
            {
                String txt = iter.next();
                logWriter.write(txt);
                extLogWriter.write(txt);
                if (iter.hasNext())
                {
                    logWriter.newLine();
                    extLogWriter.newLine();
                }
            }
            logWriter.flush();
            extLogWriter.flush();
            extLogWriter.close();
        }
        else
        {
            while (iter.hasNext())
            {
                String txt = iter.next();
                logWriter.write(txt);
                if (iter.hasNext())
                {
                    logWriter.newLine();
                }
            }
            logWriter.flush();
        }
        jar.closeEntry();
    }

    /**
     * Writes executables to execute on uninstall.
     *
     * @throws IOException for any I/O error
     */
    private void writeExecutables() throws IOException
    {
        jar.putNextEntry(new JarEntry("executables"));
        ObjectOutputStream execStream = new ObjectOutputStream(jar);
        execStream.writeInt(uninstallData.getExecutablesList().size());
        for (ExecutableFile file : uninstallData.getExecutablesList())
        {
            execStream.writeObject(file);
        }
        execStream.flush();
        jar.closeEntry();
    }

    /**
     * Writes the uinstaller jar file log.
     *
     * @throws IOException for any I/O error
     */
    private void writeUninstallerJarFileLog() throws IOException
    {
        BufferedWriter logWriter;
        jar.putNextEntry(new JarEntry("jarlocation.log"));
        logWriter = new BufferedWriter(new OutputStreamWriter(jar));
        logWriter.write(uninstallData.getUninstallerJarFilename());
        logWriter.newLine();
        logWriter.write(uninstallData.getUninstallerPath());
        logWriter.flush();
        jar.closeEntry();
    }

    /**
     * Writes uninstaller listeners.
     *
     * @throws IOException for any I/O error
     */
    private void writeUninstallerListeners() throws IOException
    {
        ArrayList<String> listeners = new ArrayList<String>();

        writeCustomDataResources(uninstallData.getUninstallerListeners());
        for (CustomData data : uninstallData.getUninstallerListeners())
        {
            if (data.listenerName != null)
            {
                listeners.add(data.listenerName);
            }
        }

        jar.putNextEntry(new JarEntry("uninstallerListeners"));
        ObjectOutputStream stream = new ObjectOutputStream(jar);
        stream.writeObject(listeners);
        stream.flush();
        jar.closeEntry();
    }

    /**
     * Writes uninstaller jars.
     *
     * @throws IOException for any I/O error
     */
    private void writeJars() throws IOException
    {
        writeCustomDataResources(uninstallData.getJars());
    }

    /**
     * Writes native libraries.
     *
     * @throws IOException for any I/O error
     */
    private void writeNativeLibraries() throws IOException
    {
        for (String path : uninstallData.getNativeLibraries())
        {
            writeResource(path);
        }
    }

    /**
     * Writes data from {@link com.izforge.izpack.installer.data.UninstallData#getAdditionalData()}.
     * <p/>
     * This silently ignores failures to locate custom resources, logging it instead.
     *
     * @throws IOException for any I/O error
     */
    @SuppressWarnings("unchecked")
    private void writeAdditionalUninstallData() throws IOException
    {
        Map<String, Object> additionalData = uninstallData.getAdditionalData();
        if (additionalData != null && !additionalData.isEmpty())
        {
            for (String key : additionalData.keySet())
            {
                Object content = additionalData.get(key);
                writeContent(key, content);
            }
        }
    }

    /**
     * Writes uninstall scripts.
     *
     * @throws IOException for any I/O error
     */
    private void writeScriptFiles() throws IOException
    {
        ArrayList<String> unInstallScripts = uninstallData.getUninstallScripts();
        ObjectOutputStream rootStream;
        int idx = 0;
        for (String unInstallScript : unInstallScripts)
        {
            jar.putNextEntry(new JarEntry(UninstallData.ROOTSCRIPT + Integer.toString(idx)));
            rootStream = new ObjectOutputStream(jar);
            rootStream.writeUTF(unInstallScript);
            rootStream.flush();
            jar.closeEntry();
            idx++;
        }
    }


    /**
     * Writes the resources referenced by {@link CustomData#contents}.
     *
     * @param customData the custom data to write
     * @throws IOException for any I/O error
     */
    private void writeCustomDataResources(List<CustomData> customData) throws IOException
    {
        for (CustomData data : customData)
        {
            if (data.contents != null)
            {
                for (String content : data.contents)
                {
                    writeResource(content);
                }
            }
        }
    }

    /**
     * Writes a resource to the jar, if it is not already present.
     * <p/>
     * Note that this ignores missing resources. TODO - why isn't a missing resource fatal?
     *
     * @param path the resource path
     * @throws IOException for any I/O error
     */
    private void writeResource(String path) throws IOException
    {
        for (Mergeable mergeable : pathResolver.getMergeableFromPath(path))
        {
            mergeable.merge(jar);
        }
    }

    /**
     * Writes content to the jar.
     *
     * @param path    the path to write to
     * @param content the content to write. May be an object or a <tt>ByteArrayOutputStream</tt>. No idea why for the latter. TODO
     * @throws IOException for any I/O error
     */
    private void writeContent(String path, Object content) throws IOException
    {
        jar.putNextEntry(new JarEntry(path));
        if (content instanceof ByteArrayOutputStream)
        {
            ((ByteArrayOutputStream) content).writeTo(jar);
        }
        else
        {
            ObjectOutputStream out = new ObjectOutputStream(jar);
            out.writeObject(content);
            out.flush();
        }
        jar.closeEntry();
    }

    /**
     * Creates the uninstaller jar file.
     *
     * @throws IzPackException for any I/O error
     */
    private void createOutputJar()
    {
        // Create the uninstaller directory
        String dirPath = IoHelper.translatePath(installData.getInfo().getUninstallerPath(), variableSubstitutor);
        String jarPath = dirPath + File.separator + installData.getInfo().getUninstallerName();
        File dir = new File(dirPath);
        if (!dir.exists() && !dir.mkdirs())
        {
            throw new IzPackException("Failed to create output path: " + dir);
        }

        // Log the uninstaller deletion information
        uninstallData.setUninstallerJarFilename(jarPath);
        uninstallData.setUninstallerPath(dirPath);

        // Create the jar file
        try
        {
            FileOutputStream out = new FileOutputStream(jarPath);
            jar = new JarOutputStream(new BufferedOutputStream(out));
        }
        catch (IOException exception)
        {
            throw new IzPackException("Problem writing uninstaller jar", exception);
        }

        jar.setLevel(9);
        uninstallData.addFile(jarPath, true);
    }

}
