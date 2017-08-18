package com.sap.olingo.jpa.processor.core.processor;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.persistence.EntityManager;

import org.apache.olingo.commons.api.data.Annotatable;
import org.apache.olingo.commons.api.edm.EdmFunction;
import org.apache.olingo.commons.api.edm.EdmParameter;
import org.apache.olingo.commons.api.edm.EdmPrimitiveType;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeException;
import org.apache.olingo.commons.api.edm.EdmType;
import org.apache.olingo.commons.api.ex.ODataException;
import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.ODataLibraryException;
import org.apache.olingo.server.api.ODataRequest;
import org.apache.olingo.server.api.ODataResponse;
import org.apache.olingo.server.api.serializer.SerializerException;
import org.apache.olingo.server.api.uri.UriParameter;
import org.apache.olingo.server.api.uri.UriResourceFunction;

import com.sap.olingo.jpa.metadata.core.edm.annotation.EdmFunctionType;
import com.sap.olingo.jpa.metadata.core.edm.mapper.api.JPADataBaseFunction;
import com.sap.olingo.jpa.metadata.core.edm.mapper.api.JPAEntityType;
import com.sap.olingo.jpa.metadata.core.edm.mapper.api.JPAFunction;
import com.sap.olingo.jpa.metadata.core.edm.mapper.api.JPAJavaFunction;
import com.sap.olingo.jpa.metadata.core.edm.mapper.api.JPAParameter;
import com.sap.olingo.jpa.metadata.core.edm.mapper.exception.ODataJPAModelException;
import com.sap.olingo.jpa.processor.core.api.JPAODataDatabaseProcessor;
import com.sap.olingo.jpa.processor.core.api.JPAODataRequestContextAccess;
import com.sap.olingo.jpa.processor.core.api.JPAODataSessionContextAccess;
import com.sap.olingo.jpa.processor.core.exception.ODataJPADBAdaptorException;
import com.sap.olingo.jpa.processor.core.exception.ODataJPAFilterException;
import com.sap.olingo.jpa.processor.core.exception.ODataJPAProcessorException;

/**
 * Functions as User Defined Functions, Native Query, as Criteria Builder does not provide the option to used UDFs in
 * the From clause.
 * @author Oliver Grande
 *
 */
public final class JPAFunctionRequestProcessor extends JPAOperationRequestProcessor implements JPARequestProcessor {

  private final JPAODataDatabaseProcessor dbProcessor;

  public JPAFunctionRequestProcessor(final OData odata, final JPAODataSessionContextAccess context,
      final JPAODataRequestContextAccess requestContext) throws ODataException {
    super(odata, context, requestContext);
    this.dbProcessor = context.getDatabaseProcessor();
  }

  @Override
  public void retrieveData(final ODataRequest request, final ODataResponse response, final ContentType responseFormat)
      throws ODataApplicationException, ODataLibraryException {

    Object result = null;
    final UriResourceFunction uriResourceFunction = (UriResourceFunction) uriInfo.getUriResourceParts().get(0);
    final JPAFunction jpaFunction = sd.getFunction(uriResourceFunction.getFunction());
    if (jpaFunction.getFunctionType() == EdmFunctionType.JavaClass) {
      result = processJavaFunction(uriResourceFunction, (JPAJavaFunction) jpaFunction, em);

    } else if (jpaFunction.getFunctionType() == EdmFunctionType.UserDefinedFunction)
      result = processJavaUDF(uriResourceFunction, (JPADataBaseFunction) jpaFunction, request, response,
          responseFormat);

    final EdmType returnType = uriResourceFunction.getFunction().getReturnType().getType();
    final Annotatable annotatable = convertResult(result, returnType, jpaFunction);
    serializeResult(returnType, response, responseFormat, annotatable);
  }

  private Object getValue(final EdmFunction edmFunction, final JPAParameter parameter, final String uriValue)
      throws ODataApplicationException {
    final String value = uriValue.replaceAll("'", "");
    final EdmParameter edmParam = edmFunction.getParameter(parameter.getName());
    try {
      return ((EdmPrimitiveType) edmParam.getType()).valueOfString(value, false, edmParam.getMaxLength(),
          edmParam.getPrecision(), edmParam.getScale(), true, parameter.getType());
    } catch (EdmPrimitiveTypeException e) {
      // Unable to convert value %1$s of parameter %2$s
      throw new ODataJPADBAdaptorException(ODataJPADBAdaptorException.MessageKeys.PARAMETER_CONVERSION_ERROR,
          HttpStatusCode.NOT_IMPLEMENTED, uriValue, parameter.getName());
    }
  }

  private Object processJavaFunction(final UriResourceFunction uriResourceFunction, final JPAJavaFunction jpaFunction,
      final EntityManager em) throws ODataApplicationException {

    final Constructor<?> c = jpaFunction.getConstructor();

    try {
      Object instance;
      if (c.getParameterCount() == 1)
        instance = c.newInstance(em);
      else
        instance = c.newInstance();
      final List<Object> parameter = new ArrayList<Object>();
      final Parameter[] methodParameter = jpaFunction.getMethod().getParameters();

      for (Parameter declairedParameter : Arrays.asList(methodParameter)) {
        for (UriParameter providedParameter : uriResourceFunction.getParameters()) {
          JPAParameter jpaParameter = jpaFunction.getParameter(declairedParameter.getName());
          if (jpaParameter.getName().equals(providedParameter.getName())) {
            parameter.add(getValue(uriResourceFunction.getFunction(), jpaParameter, providedParameter.getText()));
            break;
          }
        }
      }

      return jpaFunction.getMethod().invoke(instance, parameter.toArray());
    } catch (InstantiationException e) {
      throw new ODataJPAProcessorException(e, HttpStatusCode.INTERNAL_SERVER_ERROR);
    } catch (IllegalAccessException e) {
      throw new ODataJPAProcessorException(e, HttpStatusCode.INTERNAL_SERVER_ERROR);
    } catch (IllegalArgumentException e) {
      throw new ODataJPAProcessorException(e, HttpStatusCode.INTERNAL_SERVER_ERROR);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause != null && cause instanceof ODataApplicationException) {
        throw (ODataApplicationException) cause;
      } else {
        throw new ODataJPAProcessorException(e, HttpStatusCode.INTERNAL_SERVER_ERROR);
      }
    } catch (ODataJPAModelException e) {
      throw new ODataJPAProcessorException(e, HttpStatusCode.INTERNAL_SERVER_ERROR);
    }
  }

  private Object processJavaUDF(final UriResourceFunction uriResourceFunction, final JPADataBaseFunction jpaFunction,
      final ODataRequest request, final ODataResponse response, final ContentType responseFormat)
      throws SerializerException, ODataApplicationException {
    JPAEntityType returnType;
    try {
      returnType = sd.getEntity(jpaFunction.getResultParameter().getTypeFQN());
    } catch (ODataJPAModelException e) {
      throw new ODataJPAFilterException(e, HttpStatusCode.INTERNAL_SERVER_ERROR);
    }

    // dbProcessor.query

    return dbProcessor.executeFunctionQuery(uriResourceFunction, jpaFunction, returnType, em);

//    EntityCollection entityCollection;
//    final EdmEntitySet returnEntitySet = uriResourceFunction.getFunctionImport().getReturnedEntitySet();
//    try {
//      entityCollection = new JPAInstanceResultConverter(odata.createUriHelper(), sd, nr, returnEntitySet, returnType
//          .getTypeClass()).getResult();
//    } catch (ODataJPAModelException e) {
//      throw new ODataJPAProcessorException(ODataJPAProcessorException.MessageKeys.QUERY_RESULT_CONV_ERROR,
//          HttpStatusCode.INTERNAL_SERVER_ERROR, e);
//    } catch (URISyntaxException e) {
//      throw new ODataJPAProcessorException(ODataJPAProcessorException.MessageKeys.QUERY_RESULT_URI_ERROR,
//          HttpStatusCode.INTERNAL_SERVER_ERROR, e);
//    }
//
//    if (entityCollection.getEntities() != null && entityCollection.getEntities().size() > 0) {
//      final SerializerResult serializerResult = serializer.serialize(request, entityCollection);
//      createSuccessResponce(response, responseFormat, serializerResult);
//    } else
//      response.setStatusCode(HttpStatusCode.NO_CONTENT.getStatusCode());
  }
}
