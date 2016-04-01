package uk.co.mayfieldis.esb.SDSHAPI;

import java.util.Iterator;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.dataformat.zipfile.ZipFileDataFormat;
import org.apache.camel.model.dataformat.BindyType;

import uk.co.mayfieldis.FHIRConstants.FHIRCodeSystems;
import uk.co.mayfieldis.FHIRConstants.NHSTrustFHIRCodeSystems;
import uk.co.mayfieldis.dao.ConsultantEnrichwithOrganisation;
import uk.co.mayfieldis.dao.EnrichLocationwithLocation;
import uk.co.mayfieldis.dao.EnrichLocationwithOrganisation;
import uk.co.mayfieldis.dao.EnrichwithParentOrganisation;
import uk.co.mayfieldis.dao.EnrichwithUpdateType;
import uk.co.mayfieldis.dao.NHSConsultantEntities;
import uk.co.mayfieldis.dao.NHSConsultantEntitiestoFHIRPractitioner;
import uk.co.mayfieldis.dao.NHSEntities;
import uk.co.mayfieldis.dao.NHSTrustLocationEntities;
import uk.co.mayfieldis.dao.NHSTrustLocationEntitiestoFHIRLocation;

public class SDSCamelRoute extends RouteBuilder {

    @Override
    public void configure() 
    {
    	
    	ZipFileDataFormat zipFile = new ZipFileDataFormat();
    	zipFile.setUsingIterator(true);
    	
    	EnrichLocationwithLocation enrichLocationwithLocation = new EnrichLocationwithLocation();
    	EnrichLocationwithOrganisation enrichLocationwithOrganisation = new EnrichLocationwithOrganisation();
    	EnrichwithParentOrganisation enrichOrg = new EnrichwithParentOrganisation();
    	EnrichwithUpdateType enrichUpdateType = new EnrichwithUpdateType();
    	//ResourceToOrgInclude resourceToOrgInclude = new ResourceToOrgInclude();
    	NHSConsultantEntitiestoFHIRPractitioner consultanttoFHIRPractitioner = new NHSConsultantEntitiestoFHIRPractitioner(); 
    	ConsultantEnrichwithOrganisation consultantEnrichwithOrganisation = new ConsultantEnrichwithOrganisation();
    	NHSTrustLocationEntitiestoFHIRLocation trustLocationEntitiestoFHIRLocation = new NHSTrustLocationEntitiestoFHIRLocation(); 
    	
    	errorHandler(deadLetterChannel("direct:error")
        		.maximumRedeliveries(2));
            	    
    	    from("direct:error")
            	.routeId("NHS SDS Fail Handler")
            	.to("log:uk.co.mayfieldis.esb.SDSHAPI.SDSCamelRoute?level=ERROR&showAll=true");
    	
    	
    		// Should follow Practice upload otherwise practice won't exist
    	    from("scheduler://egpcur?delay=24h")
    	    	.routeId("Retrieve NHS GP and Practice Amendments Zip")
    	    	.setHeader(Exchange.HTTP_METHOD, constant("GET"))
    	    	.to("http4://systems.hscic.gov.uk/data/ods/datadownloads/monthamend/current/egpam.zip")
    	    	.to("file:C:/NHSSDS/zip?fileName=${date:now:yyyyMMdd}-egpcur.zip");
    	  
    	    from("file:C:/NHSSDS/zip?readLock=markerFile&preMove=inprogress&move=.done&include=.*.(zip)&delay=1000")
	    		.routeId("Unzip NHS Reference Files")
	    		.unmarshal(zipFile)
	    		.split(body(Iterator.class))
	    			.streaming()
	    				.to("log:uk.co.mayfieldis.esb.SDSHAPI.SDSCamelRoute.zip?level=INFO")
	    				.to("file:C:/NHSSDS/Extract")
	    			.end()
	    		.end();
    	    
    	    from("file:C:/NHSSDS/Extract?readLock=markerFile&preMove=inprogress&move=.done&include=.*.(csv)&delay=1000")
    	    	.routeId("Split CSV File")
    	    	.log("File ${header.CamelFileName}")
    	    	.choice()
    	    		.when(header(Exchange.FILE_NAME).isEqualTo("econcur.csv"))
    	    			.to("vm:ConsultantProcessing")
    	    		.when(header(Exchange.FILE_NAME).contains("location"))
    	    			.to("vm:LocationProcessing")	
    	    		.otherwise()
    					.unmarshal()
    	    			.bindy(BindyType.Csv, NHSEntities.class)
    	    			.split(body())
    	    			.wireTap("vm:LineProcessing")
    	    			.end()
    	    		.end();
    	        
    	    from("vm:ConsultantProcessing")
	    		.routeId("Process Consultant File")
	    		.log("Processing Consultant File")
	    		.unmarshal()
    			.bindy(BindyType.Csv, NHSConsultantEntities.class)
    			.split(body())
		    	.process(consultanttoFHIRPractitioner)
	    		.wireTap("activemq:Consultant")
	    		.end();
    	    
    	    from("vm:LocationProcessing")
	    		.routeId("Process Location File")
	    		.log("Processing Location File")
	    		.unmarshal()
				.bindy(BindyType.Csv, NHSTrustLocationEntities.class)
				.split(body())
				// Converts entity to FHIR
				.process(trustLocationEntitiestoFHIRLocation)
				.wireTap("activemq:Location")
				.end();
			
    	    from("activemq:Consultant")
		    	.routeId("FHIR Practitioner (Consultant)")
		    	.enrich("vm:lookupOrganisation",consultantEnrichwithOrganisation)
		    	.enrich("vm:lookup",enrichUpdateType)
		    	.filter(header(Exchange.HTTP_METHOD)
	    	    	.isEqualTo("POST"))
	    	    		.to("vm:Update")
	    	    	.end()
	    	    .filter(header(Exchange.HTTP_METHOD)
	    	    	.isEqualTo("PUT"))
	    	    		.to("vm:Update")
	    	    	.end();
	    	    
    	    from("activemq:Location")
		    	.routeId("FHIR Location")
		    	.enrich("vm:lookupOrganisation",enrichLocationwithOrganisation)
		    	.choice()
					.when(header("FHIRLocation").isNotNull())
						.enrich("vm:lookupLocation",enrichLocationwithLocation)
				.end()
		    	.enrich("vm:lookup",enrichUpdateType)
		    	.filter(header(Exchange.HTTP_METHOD)
	    	    	.isEqualTo("POST"))
	    	    		.to("vm:Update")
	    	    	.end()
	    	    .filter(header(Exchange.HTTP_METHOD)
	    	    	.isEqualTo("PUT"))
	    	    		.to("vm:Update")
	    	    		.to("vm:FileFHIR")
	    	    	.end();
	
    	    
    	    from("vm:LineProcessing")
    	    	.routeId("Process entries")
    	    	.process("entitytoHeader")
    	    	.enrich("vm:lookupOrganisation",enrichOrg)
    	    	.enrich("vm:lookup",enrichUpdateType)
    	    	.filter(header(Exchange.HTTP_METHOD)
    	    		.isEqualTo("POST"))
    	    		.to("vm:Update")
    	    	.end()
    	    	.filter(header(Exchange.HTTP_METHOD)
    	    		.isEqualTo("PUT"))
    	    		.to("vm:Update")
    	    	.end();
    	    	// Gets are discarded
    	    
    	    from("vm:Update")
    	    	.routeId("Update JPA Server")
    	    	.setHeader(Exchange.HTTP_PATH, simple("${header.FHIRResource}",String.class))
    	    	.setHeader(Exchange.HTTP_QUERY,simple("_format=xml",String.class))
		    	.log("Update type ${header.CamelHttpMethod} ${header.CamelHttpPath} ${header.CamelHttpQuery} Record Entity ID = ${header.OrganisationCode} partOf ${header.ParentOrganisationCode}")
		    	.setHeader("Prefer", simple("return=representation",String.class))
		    	.to("log:uk.co.mayfieldis.esb.SDSHAPI.SDSCamelRoute?level=INFO&showBody=true&showHeaders=true")
		    	.to("vm:hapi")
		    	.choice()
	    		.when(header(Exchange.FILE_NAME).isEqualTo("egpam.csv"))
	    			// only send updates for amendment load not a bulk load
	    			.to("http4:chft-tielive3.xthis.nhs.uk/REST/HAPI?connectionsPerRoute=60");
	    		
    	    	
    	    from("vm:lookupOrganisation")
    	    	.routeId("Lookup FHIR Organisation")
    	    	.setBody(simple(""))
    	    	.setHeader(Exchange.HTTP_METHOD, simple("GET", String.class))
    	    	.setHeader(Exchange.HTTP_PATH, simple("/Organization",String.class))
		    	.setHeader(Exchange.HTTP_QUERY,simple("identifier="+FHIRCodeSystems.URI_NHS_OCS_ORGANISATION_CODE+"|${header.ParentOrganisationCode}",String.class))
		    	.to("vm:hapi");
    	    
    	    from("vm:lookupLocation")
		    	.routeId("Lookup FHIR Location")
		    	//.log("Lookup Location ${header.FHIRLocation}")
		    	.setBody(simple(""))
		    	.setHeader(Exchange.HTTP_METHOD, simple("GET", String.class))
		    	.setHeader(Exchange.HTTP_PATH, simple("/Location",String.class))
		    	.setHeader(Exchange.HTTP_QUERY,simple("identifier="+NHSTrustFHIRCodeSystems.uriCHFTLocation+"|${header.FHIRLocation}",String.class))
		    	.to("vm:hapi");
    	    
    	    from("vm:lookup")
		    	.routeId("Lookup FHIR Resources")
		    	.setBody(simple(""))
		    	.setHeader(Exchange.HTTP_METHOD, simple("GET", String.class))
		    	.setHeader(Exchange.HTTP_PATH, simple("${header.FHIRResource}",String.class))
		    	.setHeader(Exchange.HTTP_QUERY,simple("${header.FHIRQuery}",String.class))
		    	.to("vm:hapi");
		    
    	    from("vm:hapi")
    	    	.routeId("Call FHIR Server")
    	    	.setHeader(Exchange.CONTENT_TYPE,simple("application/xml+fhir"))
    	    	.to("http4:chft-ddmirth.xthis.nhs.uk:8181/hapi-fhir-jpaserver/baseDstu2?connectionsPerRoute=60");
    	    
    	    from("vm:FileFHIR")
    			.routeId("FileStore")
    			.to("file:C:/NHSSDS/fhir?fileName=${date:now:yyyyMMdd hhmm.ss} ${header.CamelHL7MessageControl}.xml");
    }
}
