/*
 * Copyright (C) 2010 Stanford University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package edu.stanford.junction.props;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.Random;


public class NullOp implements IPropStateOperation{

	public String stringify(){
		JSONObject obj = new JSONObject();
		try{
			obj.put("type", "null");
		}catch(JSONException e){}
		return obj.toString();
	}

}