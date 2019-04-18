/*
 * Copyright (c) 2005, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.authenticator.saml2.sso.util;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.xerces.impl.Constants;
import org.apache.xerces.util.SecurityManager;
import org.opensaml.Configuration;
import org.opensaml.DefaultBootstrap;
import org.opensaml.common.xml.SAMLConstants;
import org.opensaml.saml2.core.Assertion;
import org.opensaml.xml.ConfigurationException;
import org.opensaml.xml.XMLObject;
import org.opensaml.xml.io.Unmarshaller;
import org.opensaml.xml.io.UnmarshallerFactory;
import org.opensaml.xml.io.UnmarshallingException;
import org.osgi.framework.BundleContext;
import org.osgi.util.tracker.ServiceTracker;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.wso2.carbon.base.MultitenantConstants;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.core.util.KeyStoreManager;
import org.wso2.carbon.identity.authenticator.saml2.sso.SAML2SSOAuthenticatorException;
import org.wso2.carbon.identity.authenticator.saml2.sso.internal.SAML2SSOAuthBEDataHolder;
import org.wso2.carbon.registry.core.service.RegistryService;
import org.wso2.carbon.registry.core.service.TenantRegistryLoader;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.utils.AuthenticationObserver;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

public class Util {
    private Util(){

    }

    private static final int ENTITY_EXPANSION_LIMIT = 0;
    private static boolean bootStrapped = false;
    private static TenantRegistryLoader tenantRegistryLoader;
    private static Log log = LogFactory.getLog(Util.class);

    /**
     * Constructing the XMLObject Object from a String
     *
     * @param authReqStr
     * @return Corresponding XMLObject which is a SAML2 object
     * @throws org.wso2.carbon.identity.authenticator.saml2.sso.SAML2SSOAuthenticatorException
     */
    public static XMLObject unmarshall(String authReqStr) throws SAML2SSOAuthenticatorException {

        XMLObject response;
        try {
            doBootstrap();

            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            documentBuilderFactory.setIgnoringComments(true);
            Document document = getDocument(documentBuilderFactory, authReqStr);
            if (isSignedWithComments(document)) {
                documentBuilderFactory.setIgnoringComments(false);
                document = getDocument(documentBuilderFactory, authReqStr);
            }
            Element element = document.getDocumentElement();
            UnmarshallerFactory unmarshallerFactory = Configuration.getUnmarshallerFactory();
            Unmarshaller unmarshaller = unmarshallerFactory.getUnmarshaller(element);
            response = unmarshaller.unmarshall(element);
            // Check for duplicate samlp:Response
            NodeList list = response.getDOM().getElementsByTagNameNS(SAMLConstants.SAML20P_NS, "Response");
            if (list.getLength() > 0) {
                log.error("Invalid schema for the SAML2 response. Multiple responses detected");
                throw new SAML2SSOAuthenticatorException("Error occurred while processing saml2 response");
            }

            NodeList assertionList = response.getDOM().getElementsByTagNameNS(SAMLConstants.SAML20_NS, "Assertion");
            if (response instanceof Assertion) {
                if (assertionList.getLength() > 0) {
                    log.error("Invalid schema for the SAML2 assertion. Multiple assertions detected");
                    throw new SAML2SSOAuthenticatorException("Error occurred while processing saml2 response");
                }
            } else {
                if (assertionList.getLength() > 1) {
                    log.error("Invalid schema for the SAML2 response. Multiple assertions detected");
                    throw new SAML2SSOAuthenticatorException("Error occurred while processing saml2 response");
                }
            }
            return response;
        } catch (ParserConfigurationException | SAXException | IOException | UnmarshallingException e) {
            log.error("Error occured while processing saml2 response");
            throw new SAML2SSOAuthenticatorException("Error occured while processing saml2 response",e);
        }

    }

    /**
     * This method is used to initialize the OpenSAML2 library. It calls the bootstrap method, if it
     * is not initialized yet.
     */
    public static void doBootstrap() {

        if (!bootStrapped) {
            try {
                DefaultBootstrap.bootstrap();
                bootStrapped = true;
            } catch (ConfigurationException e) {
                log.error("Error in bootstrapping the OpenSAML2 library", e);
            }
        }
    }

    /**
     * Get the X509CredentialImpl object for a particular tenant
     *
     * @param domainName domain name
     * @return X509CredentialImpl object containing the public certificate of that tenant
     * @throws org.wso2.carbon.identity.authenticator.saml2.sso.SAML2SSOAuthenticatorException Error when creating X509CredentialImpl object
     */
    public static X509CredentialImpl getX509CredentialImplForTenant(String domainName)
            throws SAML2SSOAuthenticatorException {

        int tenantID = MultitenantConstants.SUPER_TENANT_ID;
        RealmService realmService = SAML2SSOAuthBEDataHolder.getInstance().getRealmService();

        // get the tenantID
        if (!domainName.equals(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME)) {
            try {
                tenantID = realmService.getTenantManager().getTenantId(domainName);
            } catch (org.wso2.carbon.user.api.UserStoreException e) {
                String errorMsg = "Error getting the TenantID for the domain name";
                log.error(errorMsg, e);
                throw new SAML2SSOAuthenticatorException(errorMsg, e);
            }
        }

        KeyStoreManager keyStoreManager = null;
        X509CredentialImpl credentialImpl = null;
        try {
            Util.initializeRegistry(tenantID, domainName);
            // get an instance of the corresponding Key Store Manager instance
            keyStoreManager = KeyStoreManager.getInstance(tenantID);

            if (tenantID != MultitenantConstants.SUPER_TENANT_ID) {
                // for non zero tenants, load private key from their generated key store

                KeyStore keystore = keyStoreManager.getKeyStore(generateKSNameFromDomainName(domainName));
                java.security.cert.X509Certificate cert =
                        (java.security.cert.X509Certificate) keystore.getCertificate(domainName);
                credentialImpl = new X509CredentialImpl(cert);
            } else {    // for tenant zero, load the cert corresponding to given alias in authenticators.xml
                String alias = SAML2SSOAuthBEDataHolder.getInstance().getIdPCertAlias();
                java.security.cert.X509Certificate cert = null;
                if (alias != null) {
                    cert = (X509Certificate) keyStoreManager.getPrimaryKeyStore().getCertificate(alias);
                    if (cert == null) {
                        String errorMsg = "Cannot find a certificate with the alias " + alias +
                                " in the default key store. Please check the 'IdPCertAlias' property in" +
                                " the SSO configuration of the authenticators.xml";
                        log.error(errorMsg);
                        throw new SAML2SSOAuthenticatorException(errorMsg);
                    }
                } else { // if the idpCertAlias is not given, use the default certificate.
                    cert = keyStoreManager.getDefaultPrimaryCertificate();
                }
                credentialImpl = new X509CredentialImpl(cert);
            }
        } catch (Exception e) {
            String errorMsg = "Error instantiating an X509CredentialImpl object for the public cert.";
            log.error(errorMsg, e);
            throw new SAML2SSOAuthenticatorException(errorMsg, e);
        }
        return credentialImpl;
    }

    /**
     * Generate the key store name from the domain name
     *
     * @param tenantDomain tenant domain name
     * @return key store file name
     */
    private static String generateKSNameFromDomainName(String tenantDomain) {
        String ksName = tenantDomain.trim().replace(".", "-");
        return (ksName + ".jks");
    }

    /**
     * Return whether SAML Assertion has the canonicalization method
     * set to 'http://www.w3.org/2001/10/xml-exc-c14n#WithComments'.
     *
     * @param document
     * @return true if canonicalization method equals to 'http://www.w3.org/2001/10/xml-exc-c14n#WithComments'
     */
    private static boolean isSignedWithComments(Document document) {

        XPath xPath = XPathFactory.newInstance().newXPath();
        try {
            String assertionId = (String) xPath.compile("//*[local-name()='Assertion']/@ID")
                    .evaluate(document, XPathConstants.STRING);

            if (StringUtils.isBlank(assertionId)) {
                return false;
            }

            NodeList nodeList = ((NodeList) xPath.compile(
                    "//*[local-name()='Assertion']" +
                            "/*[local-name()='Signature']" +
                            "/*[local-name()='SignedInfo']" +
                            "/*[local-name()='Reference'][@URI='#" + assertionId + "']" +
                            "/*[local-name()='Transforms']" +
                            "/*[local-name()='Transform']" +
                            "[@Algorithm='http://www.w3.org/2001/10/xml-exc-c14n#WithComments']")
                    .evaluate(document, XPathConstants.NODESET));
            return nodeList != null && nodeList.getLength() > 0;
        } catch (XPathExpressionException e) {
            String message = "Failed to find the canonicalization algorithm of the assertion. Defaulting to: " +
                    "http://www.w3.org/2001/10/xml-exc-c14n#";
            log.warn(message);
            if (log.isDebugEnabled()) {
                log.debug(message, e);
            }
            return false;
        }
    }

    private static Document getDocument(DocumentBuilderFactory documentBuilderFactory, String samlString)
            throws IOException, SAXException, ParserConfigurationException {

        documentBuilderFactory.setNamespaceAware(true);
        documentBuilderFactory.setXIncludeAware(false);
        documentBuilderFactory.setExpandEntityReferences(false);
        try {
            documentBuilderFactory.setFeature(Constants.SAX_FEATURE_PREFIX + Constants
                    .EXTERNAL_GENERAL_ENTITIES_FEATURE, false);
            documentBuilderFactory.setFeature(Constants.SAX_FEATURE_PREFIX + Constants
                    .EXTERNAL_PARAMETER_ENTITIES_FEATURE, false);
            documentBuilderFactory.setFeature(Constants.XERCES_FEATURE_PREFIX + Constants.LOAD_EXTERNAL_DTD_FEATURE,
                    false);
            documentBuilderFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

        } catch (ParserConfigurationException e) {
            log.error("Failed to load XML Processor Feature " + Constants.EXTERNAL_GENERAL_ENTITIES_FEATURE + " or "
                    + Constants.EXTERNAL_PARAMETER_ENTITIES_FEATURE + " or " + Constants.LOAD_EXTERNAL_DTD_FEATURE +
                    " or secure-processing.", e);
        }

        SecurityManager securityManager = new SecurityManager();
        securityManager.setEntityExpansionLimit(ENTITY_EXPANSION_LIMIT);
        documentBuilderFactory.setAttribute(Constants.XERCES_PROPERTY_PREFIX + Constants.SECURITY_MANAGER_PROPERTY, 
                securityManager);

        DocumentBuilder docBuilder = documentBuilderFactory.newDocumentBuilder();
        return docBuilder.parse(new ByteArrayInputStream(samlString.trim().getBytes()));
    }

    public static void initializeRegistry(int tenantId, String tenantDomain) throws Exception {

        if (tenantId != org.wso2.carbon.utils.multitenancy.MultitenantConstants.SUPER_TENANT_ID) {
            try {
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
                carbonContext.setTenantDomain(tenantDomain, true);
                BundleContext bundleContext = SAML2SSOAuthBEDataHolder.getInstance().getBundleContext();
                if (bundleContext != null) {
                    ServiceTracker tracker = new ServiceTracker(bundleContext, AuthenticationObserver.class.getName(), null);
                    tracker.open();
                    Object[] services = tracker.getServices();
                    if (services != null) {
                        for (Object service : services) {
                            ((AuthenticationObserver) service).startedAuthentication(tenantId);
                        }
                    }
                    tracker.close();
                    try {
                        Util.getTenantRegistryLoader().loadTenantRegistry(tenantId);
                    } catch (Exception e) {
                        throw new Exception("Error loading tenant registry for tenant domain " + tenantDomain, e);
                    }
                    try {
                        RegistryService registryService = SAML2SSOAuthBEDataHolder.getInstance().getRegistryService();
                        registryService.getGovernanceSystemRegistry(tenantId);
                    } catch (Exception e) {
                        throw new Exception("Error obtaining governance system registry for tenant domain " +
                                tenantDomain, e);
                    }
                }
            } finally {
                PrivilegedCarbonContext.endTenantFlow();
            }
        }
    }

    public static TenantRegistryLoader getTenantRegistryLoader() {
        return tenantRegistryLoader;
    }

    public static void setTenantRegistryLoader(TenantRegistryLoader tenantRegistryLoader) {
        Util.tenantRegistryLoader = tenantRegistryLoader;
    }

}
