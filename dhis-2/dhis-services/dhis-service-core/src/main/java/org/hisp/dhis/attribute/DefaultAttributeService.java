package org.hisp.dhis.attribute;

/*
 * Copyright (c) 2004-2019, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.google.common.base.Preconditions.checkNotNull;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.SessionFactory;
import org.hisp.dhis.attribute.exception.MissingMandatoryAttributeValueException;
import org.hisp.dhis.attribute.exception.NonUniqueAttributeValueException;
import org.hisp.dhis.cache.Cache;
import org.hisp.dhis.cache.CacheProvider;
import org.hisp.dhis.common.IdentifiableObject;
import org.hisp.dhis.common.IdentifiableObjectManager;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.commons.util.SystemUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * @author Morten Olav Hansen <mortenoh@gmail.com>
 */
@Service( "org.hisp.dhis.attribute.AttributeService" )
public class DefaultAttributeService
    implements AttributeService
{
    private static final Predicate<AttributeValue> SHOULD_DELETE_ON_UPDATE =
        ( attributeValue ) ->
            attributeValue.getValue() == null && attributeValue.getAttribute().getValueType() == ValueType.TRUE_ONLY;

    private Cache<Attribute> attributeCache;

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final AttributeStore attributeStore;

    private final IdentifiableObjectManager manager;

    private final CacheProvider cacheProvider;

    private SessionFactory sessionFactory;

    private final Environment env;

    public DefaultAttributeService( AttributeStore attributeStore, IdentifiableObjectManager manager,
        CacheProvider cacheProvider, SessionFactory sessionFactory, Environment env )
    {
        checkNotNull( attributeStore );
        checkNotNull( manager );
        checkNotNull( cacheProvider );

        this.attributeStore = attributeStore;
        this.manager = manager;
        this.cacheProvider = cacheProvider;
        this.sessionFactory = sessionFactory;
        this.env = env;
    }

    @PostConstruct
    public void init()
    {
        attributeCache = cacheProvider.newCacheBuilder( Attribute.class )
            .forRegion( "metadataAttributes" )
            .expireAfterWrite( 12, TimeUnit.HOURS )
            .withMaximumSize( SystemUtils.isTestRun( env.getActiveProfiles() ) ? 0 : 10000 ).build();
    }

    // -------------------------------------------------------------------------
    // Attribute implementation
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public void addAttribute( Attribute attribute )
    {
        attributeStore.save( attribute );
    }

    @Override
    @Transactional
    public void updateAttribute( Attribute attribute )
    {
        attributeCache.invalidate( attribute.getUid() );
        attributeStore.update( attribute );
    }

    @Override
    @Transactional
    public void deleteAttribute( Attribute attribute )
    {
        attributeCache.invalidate( attribute.getUid() );
        attributeStore.delete( attribute );
    }

    @Override
    @Transactional(readOnly = true)
    public Attribute getAttribute( long id )
    {
        return attributeStore.get( id );
    }

    @Override
    @Transactional(readOnly = true)
    public Attribute getAttribute( String uid )
    {
        Optional<Attribute> attribute = attributeCache.get( uid, attr -> attributeStore.getByUid( uid ) );
        return attribute.isPresent() ? attribute.get() : null;
    }

    @Override
    @Transactional(readOnly = true)
    public Attribute getAttributeByName( String name )
    {
        return attributeStore.getByName( name );
    }

    @Override
    @Transactional(readOnly = true)
    public Attribute getAttributeByCode( String code )
    {
        return attributeStore.getByCode( code );
    }

    @Override
    @Transactional(readOnly = true)
    public List<Attribute> getAllAttributes()
    {
        return new ArrayList<>( attributeStore.getAll() );
    }

    @Override
    @Transactional(readOnly = true)
    public List<Attribute> getAttributes( Class<?> klass )
    {
        return new ArrayList<>( attributeStore.getAttributes( klass ) );
    }

    @Override
    @Transactional(readOnly = true)
    public List<Attribute> getMandatoryAttributes( Class<?> klass )
    {
        return new ArrayList<>( attributeStore.getMandatoryAttributes( klass ) );
    }

    @Override
    @Transactional(readOnly = true)
    public List<Attribute> getUniqueAttributes( Class<?> klass )
    {
        return new ArrayList<>( attributeStore.getUniqueAttributes( klass ) );
    }

    // -------------------------------------------------------------------------
    // AttributeValue implementation
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public <T extends IdentifiableObject> void addAttributeValue( T object, AttributeValue attributeValue ) throws NonUniqueAttributeValueException
    {
        if ( object == null || attributeValue == null || attributeValue.getAttribute() == null )
        {
            return;
        }

        Attribute attribute = getAttribute( attributeValue.getAttribute().getUid() );

        if ( Objects.isNull( attribute ) || !attribute.getSupportedClasses().contains( object.getClass() ) )
        {
            return;
        }
        if ( attribute.isUnique() )
        {

            if (  !manager.isAttributeValueUnique( object.getClass(), object, attributeValue) )
            {
                throw new NonUniqueAttributeValueException( attributeValue );
            }
        }

        object.getAttributeValues().add( attributeValue );
        sessionFactory.getCurrentSession().save( object );
    }

    @Override
    @Transactional
    public <T extends IdentifiableObject> void updateAttributeValue( T object, AttributeValue attributeValue ) throws NonUniqueAttributeValueException
    {
        Attribute attribute = getAttribute( attributeValue.getAttribute().getUid() );

        if ( object == null || attributeValue == null || Objects.isNull( attribute )
            || !attribute.getSupportedClasses().contains( object.getClass() ) )
        {
            return;
        }

        if ( attribute.isUnique() )
        {
            if ( !manager.isAttributeValueUnique( object.getClass(), object, attributeValue ) )
            {
                throw new NonUniqueAttributeValueException( attributeValue );
            }
        }

        object.setAttributeValues( object.getAttributeValues().stream()
                .filter( av -> !av.getAttribute().equals( attribute.getUid() ) ).collect(Collectors.toSet() ) );
        sessionFactory.getCurrentSession().update( object );
    }

    @Override
    @Transactional
    public <T extends IdentifiableObject> void deleteAttributeValue( T object, AttributeValue attributeValue )
    {
        object.getAttributeValues()
                .removeIf( a -> a.getAttribute() == attributeValue.getAttribute() );
        manager.update( object );
    }

    @Override
    @Transactional
    public <T extends IdentifiableObject> void deleteAttributeValues( T object, Set<AttributeValue> attributeValues )
    {
        object.getAttributeValues().removeAll( attributeValues );

        manager.update( object );
    }

    @Override
    @Transactional
    public <T extends IdentifiableObject> void updateAttributeValues( T object, List<String> jsonAttributeValues ) throws Exception
    {
        updateAttributeValues( object, getJsonAttributeValues( jsonAttributeValues ) );
    }

    @Override
    @Transactional
    public <T extends IdentifiableObject> void updateAttributeValues( T object, Set<AttributeValue> attributeValues ) throws Exception
    {
        if ( attributeValues.isEmpty() )
        {
            return;
        }

        Set<AttributeValue> toBeDeleted = attributeValues.stream()
            .filter( SHOULD_DELETE_ON_UPDATE )
            .collect( Collectors.toSet() );

        Map<String, AttributeValue> attributeValueMap = attributeValues.stream()
            .filter( SHOULD_DELETE_ON_UPDATE.negate() )
            .collect( Collectors.toMap( av -> av.getAttribute().getUid(), av -> av ) );

        Iterator<AttributeValue> iterator = object.getAttributeValues().iterator();

        List<Attribute> mandatoryAttributes = getMandatoryAttributes( object.getClass() );

        while ( iterator.hasNext() )
        {
            AttributeValue attributeValue = iterator.next();

            Attribute attribute = getAttribute( attributeValue.getAttribute().getUid() );

            if ( attributeValueMap.containsKey( attributeValue.getAttribute().getUid() ) )
            {
                AttributeValue av = attributeValueMap.get( attributeValue.getAttribute().getUid() );

                if ( attribute.isUnique() )
                {
                    if ( manager.isAttributeValueUnique( object.getClass(), object, attribute, av.getValue() ) )
                    {
                        attributeValue.setValue( av.getValue() );
                    }
                    else
                    {
                        throw new NonUniqueAttributeValueException( attributeValue, av.getValue() );
                    }
                }
                else
                {
                    attributeValue.setValue( av.getValue() );
                }

                attributeValueMap.remove( attributeValue.getAttribute().getUid() );
                mandatoryAttributes.remove( attributeValue.getAttribute() );
            }
            else
            {
                iterator.remove();
            }
        }

        for ( String uid : attributeValueMap.keySet() )
        {
            AttributeValue attributeValue = attributeValueMap.get( uid );
            addAttributeValue( object, attributeValue );
            mandatoryAttributes.remove( attributeValue.getAttribute() );
        }

        deleteAttributeValues( object, toBeDeleted );

        for ( AttributeValue attributeValue : toBeDeleted )
        {
            mandatoryAttributes.remove( attributeValue.getAttribute() );
        }


        if ( !mandatoryAttributes.isEmpty() )
        {
            throw new MissingMandatoryAttributeValueException( mandatoryAttributes );
        }
    }

    @Override
    @Transactional( readOnly = true )
    public <T extends IdentifiableObject> void generateAttributes( List<T> entityList )
    {
        entityList.forEach( entity -> entity.getAttributeValues()
            .forEach( attributeValue -> attributeValue.setAttribute( getAttribute( attributeValue.getAttribute().getUid() ) ) ) );
    }

    //--------------------------------------------------------------------------------------------------
    // Helpers
    //--------------------------------------------------------------------------------------------------

    private Set<AttributeValue> getJsonAttributeValues( List<String> jsonAttributeValues )
        throws IOException
    {
        Set<AttributeValue> attributeValues = new HashSet<>();

        Map<String, String> attributeValueMap = jsonToMap( jsonAttributeValues );

        for ( Map.Entry<String, String> entry : attributeValueMap.entrySet() )
        {
            String id = entry.getKey();
            String value = entry.getValue();

            Attribute attribute = getAttribute( id );

            if ( attribute == null )
            {
                continue;
            }

            AttributeValue attributeValue = parseAttributeValue( attribute, value );

            if ( attributeValue == null )
            {
                continue;
            }

            attributeValues.add( attributeValue );
        }

        return attributeValues;
    }

    /**
     * Parse and create AttributeValue from attribute, id and string value.
     * Sets null for all non-"true" TRUE_ONLY AttributeValues.
     */
    private AttributeValue parseAttributeValue( Attribute attribute, String value )
    {
        AttributeValue attributeValue = null;

        if ( attribute.getValueType() == ValueType.TRUE_ONLY )
        {
            value = !StringUtils.isEmpty( value ) && "true".equalsIgnoreCase( value ) ? "true" : null;

            attributeValue = new AttributeValue( value, attribute );
        }
        else if ( !StringUtils.isEmpty( value ) )
        {
            attributeValue = new AttributeValue( value, attribute );
        }

        return attributeValue;
    }

    /**
     * Parses raw JSON into a map of ID -> Value.
     * Allows null and empty values (must be handled later).
     */
    private Map<String, String> jsonToMap( List<String> jsonAttributeValues )
        throws IOException
    {
        Map<String, String> parsed = new HashMap<>();

        ObjectMapper mapper = new ObjectMapper();

        for ( String jsonString : jsonAttributeValues )
        {
            JsonNode node = mapper.readValue( jsonString, JsonNode.class );

            JsonNode nId = node.get( "id" );
            JsonNode nValue = node.get( "value" );

            if ( nId == null || nId.isNull() )
            {
                continue;
            }

            parsed.put( nId.asText(), nValue.asText() );
        }

        return parsed;
    }
}
