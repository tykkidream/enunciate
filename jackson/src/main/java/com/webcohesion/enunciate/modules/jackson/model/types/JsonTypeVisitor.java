/*
 * Copyright 2006-2008 Web Cohesion
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.webcohesion.enunciate.modules.jackson.model.types;

import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.webcohesion.enunciate.EnunciateException;
import com.webcohesion.enunciate.javac.decorations.Annotations;
import com.webcohesion.enunciate.javac.decorations.DecoratedProcessingEnvironment;
import com.webcohesion.enunciate.javac.decorations.type.DecoratedTypeMirror;
import com.webcohesion.enunciate.modules.jackson.EnunciateJacksonContext;
import com.webcohesion.enunciate.modules.jackson.model.TypeDefinition;
import com.webcohesion.enunciate.modules.jackson.model.adapters.AdapterType;
import com.webcohesion.enunciate.modules.jackson.model.util.JacksonUtil;
import com.webcohesion.enunciate.modules.jackson.model.util.MapType;

import javax.lang.model.element.Element;
import javax.lang.model.type.*;
import javax.lang.model.util.SimpleTypeVisitor6;
import java.util.concurrent.Callable;

/**
 * Utility visitor for discovering the xml types of type mirrors.
 *
 * @author Ryan Heaton
 */
public class JsonTypeVisitor extends SimpleTypeVisitor6<JsonType, JsonTypeVisitor.Context> {

  @Override
  protected JsonType defaultAction(TypeMirror typeMirror, Context context) {
    throw new EnunciateException(typeMirror + " is not recognized as an XML type.");
  }

  @Override
  public JsonType visitPrimitive(PrimitiveType primitiveType, Context context) {
    if (context.isInArray() && (primitiveType.getKind() == TypeKind.BYTE)) {
      //special case for byte[]
      return KnownJsonType.STRING; //todo: make sure this is correct serialization of byte[].
    }
    else {
      return new JsonPrimitiveType(primitiveType);
    }
  }

  @Override
  public JsonType visitDeclared(DeclaredType declaredType, Context context) {
    Element declaredElement = declaredType.asElement();
    final JsonSerialize serializeInfo = declaredElement.getAnnotation(JsonSerialize.class);

    if (serializeInfo != null) {
      DecoratedProcessingEnvironment env = context.getContext().getContext().getProcessingEnvironment();
      DecoratedTypeMirror using = Annotations.mirrorOf(new Callable<Class<?>>() {
        @Override
        public Class<?> call() throws Exception {
          return serializeInfo.using();
        }
      }, env, JsonSerializer.None.class);

      if (using != null) {
        //custom serializer; just say it's an object.
        return KnownJsonType.OBJECT;
      }

      DecoratedTypeMirror as = Annotations.mirrorOf(new Callable<Class<?>>() {
        @Override
        public Class<?> call() throws Exception {
          return serializeInfo.as();
        }
      }, env, Void.class);

      if (as != null) {
        return (JsonType) as.accept(this, context);
      }
    }

    AdapterType adapterType = JacksonUtil.findAdapterType(declaredElement, context.getContext());
    if (adapterType != null) {
      adapterType.getAdaptingType().accept(this, context);
    }
    else {
      MapType mapType = MapType.findMapType(declaredType, context.getContext());
      if (mapType != null) {
        JsonType keyType = JsonTypeFactory.getJsonType(mapType.getKeyType(), context.getContext());
        JsonType valueType = JsonTypeFactory.getJsonType(mapType.getValueType(), context.getContext());
        return new JsonMapType(keyType, valueType);
      }
      else {
        switch (declaredElement.getKind()) {
          case ENUM:
          case CLASS:
            JsonType knownType = context.getContext().getKnownType(declaredElement);
            if (knownType != null) {
              return knownType;
            }
            else {
              //type not known, not specified.  Last chance: look for the type definition.
              TypeDefinition typeDefinition = context.getContext().findTypeDefinition(declaredElement);
              if (typeDefinition != null) {
                return new JsonClassType(typeDefinition);
              }
            }
            break;
          case INTERFACE:
            if (context.isInCollection()) {
              return KnownJsonType.OBJECT;
            }
            break;
        }
      }
    }

    return super.visitDeclared(declaredType, context);
  }

  @Override
  public JsonType visitArray(ArrayType arrayType, Context context) {
    if (context.isInArray()) {
      throw new UnsupportedOperationException("Enunciate doesn't yet support multi-dimensional arrays.");
    }

    return arrayType.getComponentType().accept(this, context);
  }

  @Override
  public JsonType visitTypeVariable(TypeVariable typeVariable, Context context) {
    TypeMirror bound = typeVariable.getUpperBound();
    if (bound == null) {
      return KnownJsonType.OBJECT;
    }
    else {
      return bound.accept(this, context);
    }
  }

  @Override
  public JsonType visitWildcard(WildcardType wildcardType, Context context) {
    TypeMirror bound = wildcardType.getExtendsBound();
    if (bound == null) {
      return KnownJsonType.OBJECT;
    }
    else {
      return bound.accept(this, context);
    }
  }

  public static class Context {

    private final EnunciateJacksonContext context;
    private final boolean inArray;
    private final boolean inCollection;

    public Context(EnunciateJacksonContext context, boolean inArray, boolean inCollection) {
      this.context = context;
      this.inArray = inArray;
      this.inCollection = inCollection;
    }

    public EnunciateJacksonContext getContext() {
      return context;
    }

    public boolean isInArray() {
      return inArray;
    }

    public boolean isInCollection() {
      return inCollection;
    }
  }
}
