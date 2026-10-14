#!/usr/bin/env python3
"""舊 mappings 的純名稱轉接。Native descriptor／codec 的差異由逐版或 legacy-shared 來源處理。"""
import argparse,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def main():
    parser=argparse.ArgumentParser();parser.add_argument('module',choices=['mc1_20_1','mc1_20_4']);args=parser.parse_args()
    module=ROOT/'fabric'/args.module;output=module/'build/generated/shared';shutil.rmtree(output,ignore_errors=True)
    families=[module/'src/main/java',ROOT/'fabric/legacy-shared/src/main/java']
    sources={}
    for folder in [ROOT/'fabric/shared/src/main/java',ROOT/'fabric/legacy-shared/src/main/java']:
        for source in folder.rglob('*.java'):sources[source.relative_to(folder)]=source
    for relative,source in sorted(sources.items()):
        if (module/'src/main/java'/relative).exists():continue
        text=source.read_text().replace('net.minecraft.resources.Identifier','net.minecraft.resources.ResourceLocation')
        text=text.replace('Identifier.parse(', 'ResourceLocation.tryParse(').replace('Identifier.withDefaultNamespace(', 'new ResourceLocation(')
        text=text.replace('Identifier', 'ResourceLocation').replace('.identifier()', '.location()')
        text=text.replace('ResourceLocation.parse(', 'ResourceLocation.tryParse(').replace('ResourceLocation.withDefaultNamespace(', 'ResourceLocation.tryParse(')
        text=text.replace('.nameAndId().name()', '.getGameProfile().getName()')
        text=text.replace('source.getClient().getConnection().send(new ServerboundChatCommandPacket("wg"+(args.isEmpty() ? "" : " "+args)))', 'ClientPlatform.sendCommand(source.getClient().getConnection(), "wg"+(args.isEmpty() ? "" : " "+args))')
        text=text.replace('connection.send(new net.minecraft.network.protocol.game.ServerboundChatCommandPacket(command))', 'ClientPlatform.sendCommand(connection, command)')
        text=text.replace('Commands.trimOptionalPrefix(command)', '(command.startsWith("/") ? command.substring(1) : command)')
        text=text.replace('.getMinY()', '.getMinBuildHeight()').replace('.getMaxY()', '.getMaxBuildHeight()')
        text=text.replace('.getMinSectionY()', '.getMinSection()').replace('.getMaxSectionY()', '.getMaxSection() - 1')
        text=text.replace('net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg', 'net.minecraft.world.entity.projectile.ThrownEgg')
        text=text.replace('.markUnsaved()', '.setUnsaved(true)').replace('method="markUnsaved"', 'method="setUnsaved"')
        if args.module=='mc1_20_4':text=text.replace('mouseScrolled(double mx, double my, double sy)', 'mouseScrolled(double mx, double my, double sx, double sy)')
        target=output/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text)
if __name__=='__main__':main()
