package org.worldgit.paper;
import com.mojang.brigadier.arguments.ArgumentType;
/** Native wire type supplied by a version-specific command registrar. */
public interface WireArgument<T,N> extends ArgumentType<T> { ArgumentType<N> getNativeType(); }
