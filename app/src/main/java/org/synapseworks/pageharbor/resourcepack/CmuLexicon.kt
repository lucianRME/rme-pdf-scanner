package org.synapseworks.pageharbor.resourcepack

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** English pronunciation records, not definitions, translation or inference. */
class CmuLexicon private constructor(private var data: ByteArray, private var index: IntArray) : AutoCloseable {
    companion object {
        const val PACK_ID="cmudict-en-us-7479086"
        const val SOURCE_HASH="81917843c7f44ce2b094ac63873c2c7a4cf802040792c455ba3ca406891c3d22"
        const val LICENSE_HASH="bd4ce8e44170a5f9f481310ca85c51de3c4f851a65e679b40e603b143bd3542a"
        private fun hash(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
        fun load(pack:ResourcePackStore.Pack,dir:File,check:()->Unit={}):CmuLexicon {
            require(pack.id==PACK_ID && pack.version==1){"Unsupported pronunciation resource"}
            require(pack.entries.map{it.path}.toSet()==setOf("data/cmudict-00.txt","data/cmudict-01.txt","data/license.txt")){"Corrupt pronunciation resource"}
            check();val license=File(dir,"data/license.txt").inputStream().use{it.readBytesBounded(8192,check)}
            require(hash(license)==LICENSE_HASH){"Corrupt pronunciation license"}
            val data=ByteArray(3618488);var used=0
            for(name in listOf("data/cmudict-00.txt","data/cmudict-01.txt")) {
                File(dir,name).inputStream().use { input->val b=ByteArray(32768);while(true){check();val n=input.read(b);if(n<0)break;require(used+n<=data.size){"Corrupt pronunciation resource"};b.copyInto(data,used,0,n);used+=n} }
            }
            require(used==data.size && hash(data)==SOURCE_HASH){"Corrupt pronunciation resource"};return index(data,check)
        }
        private fun java.io.InputStream.readBytesBounded(max:Int,check:()->Unit):ByteArray {
            val out=java.io.ByteArrayOutputStream();val b=ByteArray(1024);while(true){check();val n=read(b);if(n<0)break;require(out.size()+n<=max);out.write(b,0,n)};return out.toByteArray()
        }
        internal fun index(data:ByteArray,check:()->Unit={}):CmuLexicon {
            require(data.isNotEmpty() && data.last()==10.toByte()){ "Malformed pronunciation resource" }
            val offsets=IntArray(140000);var count=0;var begin=0
            for(i in data.indices){if(i%32768==0)check();val v=data[i].toInt();require(v==10 || v in 32..126){"Malformed pronunciation resource"}
                if(v==10){require(i-begin in 3..256 && count<offsets.size){"Malformed pronunciation resource"};var space=begin;while(space<i && data[space]!=32.toByte())space++;require(space-begin in 1..64 && space<i-1){"Malformed pronunciation resource"};offsets[count++]=begin;begin=i+1}}
            val lex=CmuLexicon(data,offsets.copyOf(count));var comparisons=0
            fun compare(a:Int,b:Int):Int{if(++comparisons%1024==0)check();return lex.compare(a,b)}
            fun sift(start:Int,size:Int){var r=start;while(r*2+1<size){var c=r*2+1;if(c+1<size && compare(lex.index[c],lex.index[c+1])<0)c++;if(compare(lex.index[r],lex.index[c])>=0)break;val t=lex.index[r];lex.index[r]=lex.index[c];lex.index[c]=t;r=c}}
            for(i in count/2-1 downTo 0)sift(i,count)
            for(i in count-1 downTo 1){val t=lex.index[0];lex.index[0]=lex.index[i];lex.index[i]=t;sift(0,i)}
            check();return lex
        }
    }
    private fun end(offset:Int):Int {var e=offset;while(data[e]!=32.toByte())e++;if(data[e-1]==41.toByte()){var n=e-2;while(n>=offset && data[n].toInt() in 48..57)n--;if(n>=offset && data[n]==40.toByte() && n<e-2)e=n};return e}
    private fun compare(a:Int,b:Int):Int {val ae=end(a);val be=end(b);var i=0;while(a+i<ae && b+i<be){val c=data[a+i].toInt()-data[b+i].toInt();if(c!=0)return c;i++};return (ae-a)-(be-b)}
    @Synchronized fun lookup(word:String):List<String> {
        check(data.isNotEmpty()){ "Pronunciation resource closed" };require(word.length<=64){"Word limit:64 ASCII characters"};val q=word.trim().lowercase(Locale.ROOT);require(q.isNotEmpty() && q.all{it.code in 33..126}){"Enter one ASCII word"}
        fun cmp(offset:Int):Int{val e=end(offset);var i=0;while(offset+i<e && i<q.length){val c=data[offset+i].toInt()-q[i].code;if(c!=0)return c;i++};return (e-offset)-q.length}
        var lo=0;var hi=index.size;while(lo<hi){val mid=(lo+hi) ushr 1;if(cmp(index[mid])<0)lo=mid+1 else hi=mid}
        val found=mutableListOf<String>();while(lo<index.size && cmp(index[lo])==0){require(found.size<16){"Pronunciation result limit"};val s=index[lo++];var e=s;while(data[e]!=10.toByte())e++;found.add(String(data,s,e-s,Charsets.US_ASCII))};return found.sorted()
    }
    @Synchronized override fun close(){data=ByteArray(0);index=IntArray(0)}
}
