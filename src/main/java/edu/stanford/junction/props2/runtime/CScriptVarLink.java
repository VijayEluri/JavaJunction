/*
 * TinyJS
 *
 * A single-file Javascript-alike engine
 *
 * Authored By Gordon Williams <gw@pur3.co.uk>
 *
 * Copyright (C) 2009 Pur3 Ltd
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 */

package edu.stanford.junction.props2.runtime;

class CScriptVarLink {

	public String name;
	public CScriptVarLink nextSibling;
	public CScriptVarLink prevSibling;
	public CScriptVar var;
	public boolean owned;

	public CScriptVarLink(CScriptVar var, String name){
		this.name = name;
		this.nextSibling = null;
		this.prevSibling = null;
		this.var = var.ref();
		this.owned = false;
	}

	///< Copy constructor
	public CScriptVarLink(CScriptVarLink link){
		this.name = link.name;
		this.nextSibling = null;
		this.prevSibling = null;
		this.var = link.var.ref();
		this.owned = false;
	}

	///< Replace the Variable pointed to
	public void replaceWith(CScriptVar newVar){
		CScriptVar oldVar = var;
		var = newVar.ref();
		oldVar.unref();
	}

	///< Replace the Variable pointed to (just dereferences)
	public void replaceWith(CScriptVarLink newVar){
		if (newVar != null)
			replaceWith(newVar.var);
		else
			replaceWith(new CScriptVar());
	}

	public void delete(){
		var.unref();
	}
}