package org.hisp.dhis.system.jep;

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

import org.nfunk.jep.ParseException;
import org.nfunk.jep.function.PostfixMathCommand;
import org.nfunk.jep.function.PostfixMathCommandI;

import java.util.List;
import java.util.Stack;

public abstract class StandardDeviationBase
    extends PostfixMathCommand
    implements PostfixMathCommandI
{
    public StandardDeviationBase()
    {
        numberOfParameters = 1;
    }

    /**
     * Each subclass defines its variance computation.
     *
     * @param sum2 Sum of the squares of distance from the mean
     * @param n Total number of samples
     * @return the variances
     */
    protected abstract double getVariance( double sum2, double n );

    // nFunk's JEP run() method uses the raw Stack type
    @SuppressWarnings( { "rawtypes", "unchecked" } )
    public void run( Stack inStack )
        throws ParseException
    {
        checkStack( inStack );

        Object param = inStack.pop();
        List<Double> vals = CustomFunctions.checkVector( param );
        int n = vals.size();

        if ( n == 0 )
        {
            throw new NoValueException();
        }
        else
        {
            double sum = 0, sum2 = 0, mean, variance;

            for ( Double v : vals )
            {
                sum = sum + v;
            }

            mean = sum / n;

            for ( Double v : vals )
            {
                sum2 = sum2 + ((v - mean) * (v - mean));
            }

            variance = getVariance( sum2, n );

            inStack.push( new Double( Math.sqrt( variance ) ) );
        }
    }
}
