package be.mygod.text

import org.xml.sax.SAXException

/**
 * @author Mygod
 */
final class AttributeMissingException(attributeNames: String) extends SAXException(attributeNames + " required.") { }
