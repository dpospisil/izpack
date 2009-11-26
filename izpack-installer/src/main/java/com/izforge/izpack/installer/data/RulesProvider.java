package com.izforge.izpack.installer.data;

import com.izforge.izpack.adaptator.IXMLElement;
import com.izforge.izpack.adaptator.impl.XMLParser;
import com.izforge.izpack.data.AutomatedInstallData;
import com.izforge.izpack.installer.base.InstallerBase;
import com.izforge.izpack.rules.RulesEngine;
import com.izforge.izpack.util.Debug;
import org.picocontainer.injectors.ProviderAdapter;

import java.io.InputStream;
import java.io.ObjectInputStream;
import java.util.Map;

/**
 * Injection provider for rules.
 */
public class RulesProvider extends ProviderAdapter {

    /**
     * Resource name of the conditions specification
     */
    private static final String CONDITIONS_SPECRESOURCENAME = "conditions.xml";

    /**
     * Reads the conditions specification file and initializes the rules engine.
     */
    public RulesEngine loadRulesEngine(AutomatedInstallData installdata) {
        // try to load already parsed conditions
        RulesEngine res = null;
        try {
            InputStream in = InstallerBase.class.getResourceAsStream("/rules");
            ObjectInputStream objIn = new ObjectInputStream(in);
            Map rules = (Map) objIn.readObject();
            if ((rules != null) && (rules.size() != 0)) {
                res = new RulesEngine(rules, installdata);
            }
            objIn.close();
        }
        catch (Exception e) {
            Debug.trace("Can not find optional rules");
        }
        if (res != null) {
            installdata.setRules(res);
            // rules already read
            return res;
        }
        try {
            InputStream input = this.getClass().getResourceAsStream(CONDITIONS_SPECRESOURCENAME);
            if (input == null) {
                res = new RulesEngine((IXMLElement) null, installdata);
                return res;
            }
            XMLParser xmlParser = new XMLParser();

            // get the data
            IXMLElement conditionsxml = xmlParser.parse(input);
            res = new RulesEngine(conditionsxml, installdata);
        }
        catch (Exception e) {
            Debug.trace("Can not find optional resource " + CONDITIONS_SPECRESOURCENAME);
            // there seem to be no conditions
            res = new RulesEngine((IXMLElement) null, installdata);
        }
        installdata.setRules(res);
        return res;
    }
}
