"""MIT class-file access-word transformer and read-only debug diagnostic, copyright2026 Motiquies maintainers.
The diagnostic never changes output bytes; InnerClasses metadata is retained.
"""
import struct
def patch(raw,classname,class_names,field_rules):
 b=bytearray(raw);u2=lambda p:struct.unpack_from('>H',b,p)[0];u4=lambda p:struct.unpack_from('>I',b,p)[0]
 count=u2(8);p=10;cp={};classes={};i=1;changes=[]
 while i<count:
  tag=b[p];p+=1
  if tag==1:n=u2(p);p+=2;cp[i]=bytes(b[p:p+n]).decode('utf8','replace');p+=n
  elif tag==7:classes[i]=u2(p);p+=2
  elif tag in [3,4,9,10,11,12,17,18]:p+=4
  elif tag in [5,6]:p+=8;i+=1
  elif tag in [8,16,19,20]:p+=2
  elif tag==15:p+=3
  else:raise ValueError('Unknown constant pool tag')
  i+=1
 def access(pos,label,remove_final=False):
  before=u2(pos);after=(before&~7)|1
  if remove_final:after&=~16
  if before!=after:struct.pack_into('>H',b,pos,after);changes.append({'member':label,'offset':pos,'before':before,'after':after})
 if classname in class_names:access(p,'class')
 p+=6;n=u2(p);p+=2+2*n
 for kind in ['field','method']:
  n=u2(p);p+=2
  for _ in range(n):
   name=cp[u2(p+2)];attrs=u2(p+6)
   if kind=='field' and name in field_rules.get(classname,{}):access(p,name,field_rules[classname][name])
   p+=8
   for _ in range(attrs):size=u4(p+2);p+=6+size
 n=u2(p);p+=2
 for _ in range(n):
  attr=cp[u2(p)];size=u4(p+2);start=p+6
  if attr=='InnerClasses':
   for q in range(start+2,start+2+8*u2(start),8):
    ix=u2(q)
    if ix and cp[classes[ix]] in class_names:access(q+6,'InnerClasses:'+cp[classes[ix]])
  p=start+size
 assert p==len(b)
 restored=bytearray(b)
 for change in changes:struct.pack_into('>H',restored,change['offset'],change['before'])
 assert bytes(restored)==raw,'Only documented access flags may differ'
 return bytes(b),changes

def without_debug(raw):
 p=10;u2=lambda i:struct.unpack_from('>H',raw,i)[0];u4=lambda i:struct.unpack_from('>I',raw,i)[0];cp={};i=1
 while i<u2(8):
  tag=raw[p];p+=1
  if tag==1:n=u2(p);p+=2;cp[i]=raw[p:p+n].decode('utf8','replace');p+=n
  elif tag in [3,4,9,10,11,12,17,18]:p+=4
  elif tag in [5,6]:p+=8;i+=1
  elif tag in [7,8,16,19,20]:p+=2
  elif tag==15:p+=3
  else:raise ValueError(tag)
  i+=1
 head=raw[:p+6];p+=6;n=u2(p);head+=raw[p:p+2+2*n];p+=2+2*n
 def attrs(pos):
  n=u2(pos);pos+=2;out=[]
  for _ in range(n):
   idx=u2(pos);size=u4(pos+2);data=raw[pos+6:pos+6+size];start=pos+6;name=cp[idx];pos=start+size
   if name in ['LineNumberTable','LocalVariableTable','LocalVariableTypeTable','SourceFile']:continue
   if name=='Code':
    q=start+8+u4(start+4);q+=2+8*u2(q);nested,end=attrs(q);assert end==pos;data=raw[start:q]+nested
   out.append(struct.pack('>HI',idx,len(data))+data)
  return struct.pack('>H',len(out))+b''.join(out),pos
 out=head
 for _ in range(2):
  n=u2(p);out+=raw[p:p+2];p+=2
  for _ in range(n):
   out+=raw[p:p+6];p+=6;data,p=attrs(p);out+=data
 data,p=attrs(p);out+=data;assert p==len(raw);return out
