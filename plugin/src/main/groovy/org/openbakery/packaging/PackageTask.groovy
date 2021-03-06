package org.openbakery.packaging

import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.logging.text.StyledTextOutput
import org.gradle.internal.logging.text.StyledTextOutputFactory
import org.openbakery.AbstractDistributeTask
import org.openbakery.CommandRunnerException
import org.openbakery.assemble.AppPackage
import org.openbakery.bundle.ApplicationBundle
import org.openbakery.bundle.Bundle
import org.openbakery.codesign.CodesignParameters
import org.openbakery.tools.CommandLineTools
import org.openbakery.tools.Lipo
import org.openbakery.xcode.Type
import org.openbakery.XcodePlugin
import org.openbakery.codesign.ProvisioningProfileReader

class PackageTask extends AbstractDistributeTask {

	public static final String PACKAGE_PATH = "package"
	File outputPath


	private List<Bundle> appBundles

	String applicationBundleName
	StyledTextOutput output


	CodesignParameters codesignParameters = new CodesignParameters()

	PackageTask() {
		super();
		setDescription("Signs the app bundle that was created by the build and creates the ipa");
		dependsOn(
						XcodePlugin.KEYCHAIN_CREATE_TASK_NAME,
						XcodePlugin.PROVISIONING_INSTALL_TASK_NAME,
		)
		finalizedBy(
						XcodePlugin.KEYCHAIN_REMOVE_SEARCH_LIST_TASK_NAME
		)

		output = services.get(StyledTextOutputFactory).create(PackageTask)

	}


	@TaskAction
	void packageApplication() throws IOException {
		if (project.xcodebuild.isSimulatorBuildOf(Type.iOS)) {
			logger.lifecycle("not a device build, so no codesign and packaging needed")
			return
		}
		outputPath = new File(project.getBuildDir(), PACKAGE_PATH)

		File applicationFolder = createApplicationFolder()

		def applicationName = getApplicationNameFromArchive()
		copy(getApplicationBundleDirectory(), applicationFolder)

		applicationBundleName = applicationName + ".app"

		File applicationPath = new File(applicationFolder, applicationBundleName)

		// copy onDemandResources
		File onDemandResources = new File(getProductsDirectory(), "OnDemandResources")
		if (onDemandResources.exists()) {
			copy(onDemandResources, applicationPath)
		}

		File bcSymbolsMaps = new File(getArchiveDirectory(), "BCSymbolMaps")
		if (bcSymbolsMaps.exists()) {
			copy(bcSymbolsMaps, applicationFolder.parentFile)
		}

		enumerateExtensionSupportFolders(getArchiveDirectory()) { File supportDirectory ->
			copy(supportDirectory, applicationFolder.parentFile)
		}

		ApplicationBundle applicationBundle = new ApplicationBundle(applicationPath , project.xcodebuild.type, project.xcodebuild.simulator)
		appBundles = applicationBundle.getBundles()

		File resourceRules = new File(applicationFolder, applicationBundleName + "/ResourceRules.plist")
		if (resourceRules.exists()) {
			resourceRules.delete()
		}


		File infoPlist = getInfoPlistFile()

		try {
			plistHelper.deleteValueFromPlist(infoPlist, "CFBundleResourceSpecification")
		} catch (CommandRunnerException ex) {
			// ignore, this means that the CFBundleResourceSpecification was not in the infoPlist
		}

		def signSettingsAvailable = true
		if (project.xcodebuild.signing.mobileProvisionFile == null) {
			logger.warn('No mobile provision file provided.')
			signSettingsAvailable = false
		} else if (!project.xcodebuild.signing.keychainPathInternal.exists()) {
			logger.warn('No certificate or keychain found.')
			signSettingsAvailable = false
		}

		codesignParameters.mergeMissing(project.xcodebuild.signing.codesignParameters)
		codesignParameters.type = project.xcodebuild.type
		codesignParameters.keychain = project.xcodebuild.signing.keychainPathInternal


		CommandLineTools tools = new CommandLineTools(commandRunner, plistHelper, new Lipo(xcode, commandRunner))
		AppPackage appPackage = new AppPackage(applicationBundle, getArchiveDirectory(), codesignParameters, tools)

		appPackage.addSwiftSupport()

		// Todo move the lines below to the AppPackager class to preapreBundle
		// the embedProvisioningProfileToBundle is not mirgrated yet
		//appPackage.prepareBundles(applicationBundle)
		for (Bundle bundle : appBundles) {
			if (project.xcodebuild.isDeviceBuildOf(Type.iOS)) {
				appPackage.removeUnneededDylibsFromBundle(bundle)
				embedProvisioningProfileToBundle(bundle)
			}
		}

		if (signSettingsAvailable) {
			appPackage.codesign(applicationBundle, xcode)
		} else {
			String message = "Bundle not signed: " + applicationBundle
			output.withStyle(StyledTextOutput.Style.Failure).println(message)
		}

		appPackage.createPackage(outputPath, getIpaFileName())
	}


	File getProvisionFileForBundle(File bundle) {
		String bundleIdentifier = getIdentifierForBundle(bundle)
		return ProvisioningProfileReader.getReaderForIdentifier(bundleIdentifier, project.xcodebuild.signing.mobileProvisionFile, this.commandRunner, this.plistHelper).provisioningProfile
	}




	private void enumerateExtensionSupportFolders(File parentFolder, Closure closure) {
		def folderNames = ["MessagesApplicationExtensionSupport", "WatchKitSupport2"]

		for (String name in folderNames) {
			File supportFolder = new File(parentFolder, name)
			if (supportFolder.exists()) {
				closure(supportFolder)
			}
		}
	}



	private String getIdentifierForBundle(File bundle) {
		File infoPlist

		if (project.xcodebuild.isDeviceBuildOf(Type.iOS)) {
			infoPlist = new File(bundle, "Info.plist");
		} else {
			infoPlist = new File(bundle, "Contents/Info.plist")
		}

		String bundleIdentifier = plistHelper.getValueFromPlist(infoPlist, "CFBundleIdentifier")
		return bundleIdentifier
	}

	private void embedProvisioningProfileToBundle(Bundle bundle) {
		File mobileProvisionFile = getProvisionFileForBundle(bundle.path)
		if (mobileProvisionFile != null) {
			File embeddedProvisionFile

			String profileExtension = FilenameUtils.getExtension(mobileProvisionFile.absolutePath)
			embeddedProvisionFile = new File(getAppContentPath(bundle) + "embedded." + profileExtension)

			logger.info("provision profile - {}", embeddedProvisionFile)

			FileUtils.copyFile(mobileProvisionFile, embeddedProvisionFile)
		}
	}

	private File createSigningDestination(String name) throws IOException {
		File destination = new File(outputPath, name);
		if (destination.exists()) {
			FileUtils.deleteDirectory(destination);
		}
		destination.mkdirs()
		return destination
	}

	private File createApplicationFolder() throws IOException {

		if (project.xcodebuild.isDeviceBuildOf(Type.iOS)) {
			return createSigningDestination("Payload")
		} else {
			// same folder as signing
			if (!outputPath.exists()) {
				outputPath.mkdirs()
			}
			return outputPath
		}
	}

    private File getInfoPlistFile() {
		return new File(getAppContentPath() + "Info.plist")
    }

	private String getAppContentPath() {

		return getAppContentPath(appBundles.last())
	}

	private String getAppContentPath(Bundle bundle) {
		if (project.xcodebuild.type == Type.iOS) {
			return bundle.path.absolutePath + "/"
		}
		return bundle.path.absolutePath + "/Contents/"
	}

	def getIpaFileName() {
		if (project.xcodebuild.ipaFileName) {
			return project.xcodebuild.ipaFileName
		} else {
			return getApplicationNameFromArchive()
		}
	}


	String getSigningIdentity() {
		return codesignParameters.signingIdentity
	}

	void setSigningIdentity(String identity) {
		codesignParameters.signingIdentity = identity
	}
}
